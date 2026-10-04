# 2. Health checks

## The one-command check

```sh
make smoke
```

Expect 20 `ok` lines and `All checks passed.` Exit code 0 means healthy. When a check fails, its line says `FAIL` and
the script exits 1 (through `make`, which reports it as `Error 1` and exits 2). CI runs the same script on every push ("Local stack smoke test").

| Group | What it proves |
|---|---|
| Edge | `app.`, `api.`, `traefik.` answer through Traefik; an unknown host gets 404 |
| Identity | Realm `prayog` serves the right issuer; both bot clients get tokens with `aud: prayog-api` and role `bot`; a wrong secret gets 401; `trader1`/`ops1` have their roles; the web client uses PKCE |
| Data and messaging | PostgreSQL answers, the `keycloak` database exists, Redis answers PING, the Kafka topic has 3 partitions, the host listener `localhost:9094` works, auto topic creation is off |

## Each service's own health

```sh
make ps                                                   # health column per service
docker inspect --format '{{json .State.Health}}' prayog-keycloak-1 | python3 -m json.tool   # last 5 probe results
docker stats --no-stream                                  # memory and CPU per container
```

| Service | Health check | Typical memory |
|---|---|---|
| postgres | `pg_isready` | 50 MB |
| kafka | a Kafka API call on `localhost:9092` | 410 MB |
| redis | `redis-cli ping` | 10 MB |
| keycloak | `GET /health/ready` on management port 9000 | 570 MB |
| traefik | `traefik healthcheck --ping` | 20 MB |
| app-placeholder, api-placeholder | none (no shell in the image); running = OK | 5 MB |
