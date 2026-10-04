# 6. Failure drills

Break things on purpose; each drill says what should happen.

| Drill | Do | Expect | Recover |
|---|---|---|---|
| A dependency dies | `dc stop redis`, then `make smoke` | `FAIL  Redis answers PING`, `1 check(s) failed.`, non-zero exit | `make up` (returns when healthy), `make smoke` passes |
| Identity down | `dc stop keycloak`, then open the account page | `504 Gateway Timeout` from Traefik; `make smoke` fails the 7 identity checks that need Keycloak | `make up` (Keycloak is ready again in about 11 s) |
| Proxy down | `dc stop traefik` | Every `*.prayog.localhost` address refuses to connect; Postgres/Redis/Kafka on localhost still work (they don't go through Traefik) | `make up` |
| Restart keeps data | `dc exec redis redis-cli set drill 1`, `make down`, `make up`, `dc exec redis redis-cli get drill` | `"1"`: volumes survived | `dc exec redis redis-cli del drill` |
| Fresh start | `make reset`, `make up`, `make smoke` | 3 volumes removed; `make up` takes about 40 s (images already downloaded); realm imported again; all checks pass | none needed |
| Missing secrets | `mv .env .env.bak`, `make up` | `No .env: run 'make env' first.` | `mv .env.bak .env` |

`make up` is safe to run any time: it only starts what isn't running and waits for health.
