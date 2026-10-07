"""Latency and GC numbers for the exchange over a window ending at a given UTC time, from Prometheus."""
import json, subprocess, sys, urllib.parse

end, window = sys.argv[1], sys.argv[2]  # e.g. 2026-10-07T20:00:29Z 600s

def q(expr):
    url = "http://localhost:9090/api/v1/query?" + urllib.parse.urlencode({"query": expr, "time": end})
    out = subprocess.run(["docker", "exec", "prayog-prometheus-1", "wget", "-qO-", url], capture_output=True, text=True).stdout
    res = json.loads(out)["data"]["result"]
    return ", ".join(f'{r["metric"].get("gc", "")}={float(r["value"][1]):.4f}' for r in res) or "-"

for p in ("0.5", "0.9", "0.99", "0.999"):
    print(f"p{p}", q(f'histogram_quantile({p}, sum by (le) (rate(prayog_order_latency_seconds_bucket{{kind="new"}}[{window}])))'))
print("orders/s", q(f'sum(rate(prayog_order_latency_seconds_count{{kind="new"}}[{window}]))'))
print("gc pause max s", q(f'max by (gc) (max_over_time(jvm_gc_pause_seconds_max{{job=~".*exchange.*"}}[{window}]))'))
print("gc pause total s", q(f'sum by (gc) (increase(jvm_gc_pause_seconds_sum{{job=~".*exchange.*"}}[{window}]))'))
print("gc pauses", q(f'sum by (gc) (increase(jvm_gc_pause_seconds_count{{job=~".*exchange.*"}}[{window}]))'))
print("heap used max MiB", q(f'max_over_time(sum(jvm_memory_used_bytes{{job=~".*exchange.*",area="heap"}})[{window}:15s]) / 1048576'))
