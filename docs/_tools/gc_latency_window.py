"""Exchange order latency and GC numbers over a window ending at a UTC time, from Prometheus.

    python3 docs/_tools/gc_latency_window.py 2026-10-07T20:00:29Z 600s

Queries the stack's Prometheus container; used in docs/benchmarks.md (order latency tail).
"""

import json
import subprocess
import sys
import urllib.parse

END, WINDOW = sys.argv[1], sys.argv[2]
EXCHANGE = 'job=~".*exchange.*"'


def query(expr: str) -> str:
    url = "http://localhost:9090/api/v1/query?" + urllib.parse.urlencode(
        {"query": expr, "time": END}
    )
    command = ["docker", "exec", "prayog-prometheus-1", "wget", "-qO-", url]
    out = subprocess.run(command, capture_output=True, text=True, check=True).stdout
    results = json.loads(out)["data"]["result"]
    values = (f"{r['metric'].get('gc', '')}={float(r['value'][1]):.4f}" for r in results)
    return ", ".join(values) or "-"


for p in ("0.5", "0.9", "0.99", "0.999"):
    buckets = f'prayog_order_latency_seconds_bucket{{kind="new"}}[{WINDOW}]'
    print(f"p{p}", query(f"histogram_quantile({p}, sum by (le) (rate({buckets})))"))
print("orders/s", query(f'sum(rate(prayog_order_latency_seconds_count{{kind="new"}}[{WINDOW}]))'))
print(
    "gc pause max s",
    query(f"max by (gc) (max_over_time(jvm_gc_pause_seconds_max{{{EXCHANGE}}}[{WINDOW}]))"),
)
print(
    "gc pause total s",
    query(f"sum by (gc) (increase(jvm_gc_pause_seconds_sum{{{EXCHANGE}}}[{WINDOW}]))"),
)
print(
    "gc pauses",
    query(f"sum by (gc) (increase(jvm_gc_pause_seconds_count{{{EXCHANGE}}}[{WINDOW}]))"),
)
heap = f'sum(jvm_memory_used_bytes{{{EXCHANGE},area="heap"}})'
print("heap used max MiB", query(f"max_over_time({heap}[{WINDOW}:15s]) / 1048576"))
