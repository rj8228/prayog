#!/usr/bin/env bash
# End-to-end correctness of the running stack (`make e2e`):
#   1. market and bot checks through the public API (tests/e2e/market_check.py)
#   2. Kafka chaos: stop Kafka mid-session, start it again, no event lost (tests/e2e/kafka_check.py)
#   3. post-trade: ledger caught up and zero-sum, a bot's trades in its P&L and on the leaderboard, survives a restart
#      (tests/e2e/posttrade_check.py)
#   4. deterministic replay of the LIVE journal: stop the exchange, replay its journal in a one-off container,
#      require the same event-log checksum, start the exchange again (from the snapshot taken at the stop).
set -uo pipefail
cd "$(dirname "$0")/../.."
COMPOSE=(docker compose --env-file .env -f deploy/compose/compose.yaml --profile infra --profile app)
SECONDS_TO_WATCH=${E2E_SECONDS:-30}
status=0

echo "== Market and bots"
uv run python tests/e2e/market_check.py --seconds "$SECONDS_TO_WATCH" || status=1

echo
echo "== Kafka outage"
uv run python tests/e2e/kafka_check.py --outage "${E2E_KAFKA_OUTAGE:-8}" || status=1

echo
echo "== Post-trade"
uv run python tests/e2e/posttrade_check.py --outage "${E2E_POSTTRADE_OUTAGE:-5}" || status=1

echo
echo "== Replay of the live journal"
"${COMPOSE[@]}" stop exchange >/dev/null 2>&1
# The image's fat jar holds ReplayCheck; PropertiesLauncher runs a main class other than the app's.
"${COMPOSE[@]}" run --rm --no-deps --entrypoint java exchange \
  -cp /app/app.jar -Dloader.main=dev.prayog.exchange.core.journal.ReplayCheck \
  org.springframework.boot.loader.launch.PropertiesLauncher /data/journal || status=1
"${COMPOSE[@]}" up -d --wait exchange >/dev/null 2>&1 || { echo "exchange did not come back"; status=1; }
# The stop above took a snapshot, so the restart must have started from it (ADR 0016), not replayed everything.
T=$(make -s ops-token)
from=$(curl -s -H "Authorization: Bearer $T" http://api.prayog.localhost/api/v1/ops/status |
  python3 -c 'import json,sys; print(json.load(sys.stdin)["recoveredFromSnapshotInputSeq"])')
if [ "${from:-0}" -gt 0 ]; then echo "restart recovered from the snapshot at input seq $from"
else echo "FAIL: the restart did not use a snapshot"; status=1; fi

echo
if [ "$status" -eq 0 ]; then echo "E2E passed."; else echo "E2E FAILED."; fi
exit "$status"
