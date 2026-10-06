# 16. Observability

Prometheus and Grafana run in the `obs` profile ([ADR 0019](../adr/0019-observability.md)).

| Task | Command | Expect |
|---|---|---|
| Start with dashboards | `make up PROFILES="infra app obs"` | Also `prometheus` and `grafana` healthy |
| Open the dashboard | http://grafana.prayog.localhost | "Prayog exchange" as the home dashboard; no login needed to view |
| Edit dashboards | sign in as `admin`, password `GRAFANA_ADMIN_PASSWORD` from `.env` | Changes are not saved to git: edit `deploy/compose/grafana/dashboards/prayog-exchange.json` instead |
| Are targets up? | `dc exec prometheus wget -qO- localhost:9090/api/v1/targets \| python3 -m json.tool \| grep health` | `"up"` for exchange and post-trade |
| One metric by hand | `curl -s http://api.prayog.localhost/actuator/prometheus \| grep ^prayog_kafka` | lag, connected, published seq |
| Watch an outage | `dc stop kafka`, watch *Kafka lag* rise; `dc up -d --wait kafka` | The lag falls back to ~0 within seconds; *Ledger behind exchange* follows |
| Watch a fast day | Ops page: **Run a day at 300x** | *Commands / s* and *Orders by outcome* jump, *Ring in use* stays near 0 |
| Validate the config | `docker run --rm --entrypoint /bin/promtool -v "$PWD/deploy/compose/prometheus:/p:ro" prom/prometheus:v3.13.4 check config /p/prometheus.yml` | `SUCCESS` |
