"""Kafka chaos check of a running stack (part of ``make e2e``). Exits 1 if any check fails.

S15 acceptance: stop Kafka in the middle of a live session, keep trading, start it again, and
require that no event is lost.

1. The publisher is connected and keeping up.
2. Kafka stops. Trading carries on (the input sequence keeps rising) and events wait in the journal.
3. Kafka starts again. The publisher catches up to every event written during the outage.
4. The topic holds every event id from 1 to the last published one, with no gaps (duplicates are
   allowed: delivery is at-least-once). On a long-running stack with a very large topic this full
   scan is skipped unless ``--full`` is given; the counts are still checked.
"""

import argparse
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path

import httpx
from prayog_sdk.cli import load_dotenv

ROOT = Path(__file__).resolve().parents[2]
COMPOSE = [
    "docker", "compose", "--env-file", str(ROOT / ".env"), "-f",
    str(ROOT / "deploy/compose/compose.yaml"), "--profile", "infra", "--profile", "app",
]  # fmt: skip
TOPIC = "prayog.exchange.events.v1"
FULL_SCAN_LIMIT = 5_000_000
results: list[tuple[bool, str]] = []


def check(ok: bool, name: str) -> None:
    results.append((ok, name))
    print(f"  {'ok  ' if ok else 'FAIL'}  {name}", flush=True)


class Ops:
    """Calls the exchange's ops API as the ``prayog-ops-tool`` client (roles ops and admin)."""

    def __init__(self, api_url: str, auth_url: str, secret: str) -> None:
        self.api_url = api_url
        self.auth_url = auth_url
        self.secret = secret
        self.http = httpx.Client(timeout=10)
        self.token = ""

    def _token(self) -> str:
        response = self.http.post(
            f"{self.auth_url}/protocol/openid-connect/token",
            data={
                "grant_type": "client_credentials",
                "client_id": "prayog-ops-tool",
                "client_secret": self.secret,
            },
        )
        response.raise_for_status()
        return response.json()["access_token"]

    def request(self, method: str, path: str, body: dict | None = None) -> dict:
        for attempt in range(2):
            if not self.token:
                self.token = self._token()
            response = self.http.request(
                method,
                f"{self.api_url}{path}",
                json=body,
                headers={"Authorization": f"Bearer {self.token}"},
            )
            if response.status_code == 401 and attempt == 0:
                self.token = ""  # expired: fetch a new one once
                continue
            response.raise_for_status()
            return response.json()
        raise RuntimeError("unreachable")

    def status(self) -> dict:
        return self.request("GET", "/api/v1/ops/status")


def wait_for(condition, seconds: float, every: float = 0.5):
    """Polls ``condition`` until it returns something truthy or ``seconds`` pass; returns it."""
    deadline = time.monotonic() + seconds
    while True:
        value = condition()
        if value or time.monotonic() > deadline:
            return value
        time.sleep(every)


def kafka_exec(*args: str, timeout: float = 120) -> str:
    out = subprocess.run(
        [*COMPOSE, "exec", "-T", "kafka", *args],
        capture_output=True,
        text=True,
        timeout=timeout,
        check=True,
    )
    return out.stdout


def end_offsets() -> dict[int, int]:
    """Each partition's end offset: how many records it holds (duplicates included)."""
    out = kafka_exec(
        "/opt/kafka/bin/kafka-get-offsets.sh", "--bootstrap-server", "localhost:9092",
        "--topic", TOPIC,
    )  # fmt: skip
    ends = {}
    for line in out.split():
        if line.count(":") == 2:
            _, partition, offset = line.split(":")
            ends[int(partition)] = int(offset)
    return ends


def topic_event_ids(ends: dict[int, int]) -> set[int]:
    """Event ids of the first ``ends[p]`` records of each partition. Reading to a fixed count, not
    "until quiet", matters: the live market keeps adding records, so a quiet moment never comes."""
    ids: set[int] = set()
    for partition, count in ends.items():
        if count == 0:
            continue
        out = kafka_exec(
            "/opt/kafka/bin/kafka-console-consumer.sh", "--bootstrap-server", "localhost:9092",
            "--topic", TOPIC, "--partition", str(partition), "--offset", "earliest",
            "--max-messages", str(count), "--timeout-ms", "30000",
            timeout=900,
        )  # fmt: skip
        ids.update(int(m) for m in re.findall(r'"seq":(\d+)', out))
    return ids


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--outage", type=float, default=8, help="seconds Kafka stays down")
    parser.add_argument("--full", action="store_true", help="scan the whole topic however large")
    args = parser.parse_args()
    load_dotenv(ROOT / ".env")
    ops = Ops(
        os.environ.get("PRAYOG_API_URL", "http://api.prayog.localhost"),
        os.environ.get("PRAYOG_AUTH_URL", "http://auth.prayog.localhost/realms/prayog"),
        os.environ["PRAYOG_OPS_TOOL_SECRET"],
    )

    print("Kafka publisher")
    kafka = ops.status()["kafka"]
    check(kafka["enabled"], "publishing is on")
    caught_up = wait_for(
        lambda: (k := ops.status()["kafka"])["connected"] and k["lag"] < 1_000, 180
    )
    check(bool(caught_up), f"connected and keeping up ({ops.status()['kafka']})")

    print(f"Kafka down for {args.outage:.0f} s")
    subprocess.run([*COMPOSE, "stop", "kafka"], capture_output=True, check=True)
    try:
        before = ops.status()
        time.sleep(args.outage)
        during = ops.status()
        check(
            during["lastProcessedInputSeq"] > before["lastProcessedInputSeq"],
            f"trading carried on (input seq {before['lastProcessedInputSeq']} -> "
            f"{during['lastProcessedInputSeq']})",
        )
        lag = during["kafka"]["lag"]
        check(lag > 0, f"events wait in the journal (lag {lag})")
    finally:
        subprocess.run([*COMPOSE, "up", "-d", "--wait", "kafka"], capture_output=True, check=True)

    print("Kafka back")
    target = ops.status()
    target_seq = target["kafka"]["publishedSeq"] + target["kafka"]["lag"]
    reached = wait_for(lambda: ops.status()["kafka"]["publishedSeq"] >= target_seq, 120)
    check(bool(reached), f"caught up to event {target_seq} written during the outage")
    published = ops.status()["kafka"]["publishedSeq"]

    ends = end_offsets()
    size = sum(ends.values())
    check(size >= published, f"topic holds {size} records for {published} events")
    if size <= FULL_SCAN_LIMIT or args.full:
        ids = topic_event_ids(ends)
        missing = [i for i in range(1, published + 1) if i not in ids]
        check(not missing, f"every event 1..{published} is on the topic (missing {missing[:5]})")
    else:
        print(f"  skip  full scan of {size} records (use --full)")

    failed = [name for ok, name in results if not ok]
    print(f"\n{len(results) - len(failed)}/{len(results)} checks passed")
    print(json.dumps(ops.status()["kafka"]))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
