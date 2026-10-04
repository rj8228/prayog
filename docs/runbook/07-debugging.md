# 7. Debugging

## First three commands

```sh
make ps                        # which service is not healthy?
make logs SERVICE=keycloak     # follow one service's log (Ctrl-C to stop); try kafka, postgres, traefik
make smoke                     # which capability is broken?
```

## Looking closer

| Question | Command |
|---|---|
| Why is a health check failing? | `docker inspect --format '{{json .State.Health}}' prayog-<service>-1 \| python3 -m json.tool` (last probe outputs) |
| Did a container restart or crash? | `docker ps -a --filter name=prayog` (status, exit code) |
| Last 200 lines of everything | `docker compose --env-file .env -f deploy/compose/compose.yaml --profile '*' logs --tail=200` |
| Is memory the problem? | `docker stats --no-stream` |
| What is Traefik routing? | http://traefik.prayog.localhost/dashboard/ or `curl -s http://traefik.prayog.localhost/api/http/routers \| python3 -m json.tool` |
| What config did Compose actually use? | `docker compose --env-file .env -f deploy/compose/compose.yaml --profile infra config` (prints secrets: don't share it) |
| Shell inside a container | `dc exec postgres sh` (Kafka and Keycloak: `bash`) |

## Known problems

| Symptom | Cause | Fix |
|---|---|---|
| `make up` says `No .env` | Secrets not created | `make env` |
| `Cannot connect to the Docker daemon` | Docker Desktop not running | Start it, wait for the whale to settle |
| `bind: address already in use` for port 80, 5432, 6379 or 9094 | Another program uses the port | `lsof -nP -iTCP:80 -sTCP:LISTEN` shows it; stop it |
| `make up` times out on keycloak or kafka | Low memory or slow first start | `docker stats`; give Docker 4 GB+; run `make up` again |
| Realm, users or secrets don't change after editing | Imports run only on empty volumes | `make reset`, `make up` |
| Kafka client on your Mac connects, then times out | Using `kafka:9092` from the host | From the host use `localhost:9094`; inside containers use `kafka:9092` |
| Login page loops or `Invalid parameter: redirect_uri` | Wrong host name (e.g. `127.0.0.1` instead of `app.prayog.localhost`) | Always use the `*.prayog.localhost` names |
| Browser can't find `*.prayog.localhost` (Safari, some tools) | Tool doesn't map `.localhost` to your machine | Add the `/etc/hosts` line from the main README, or use Chrome/Firefox |
| A shell variable holding a command does nothing (zsh) | zsh doesn't split `$VAR` into words | Write the command out, or use the `dc` alias |
| Web app: calls fail with 401, `WWW-Authenticate: ... Jwt expired` just after signing in | The Mac slept; Docker Desktop's VM clock lags until it resyncs, so Keycloak minted an already-old token | Wait a minute (compare `date -u` with `docker exec prayog-exchange-1 date -u`), then sign in again |
| Long runs stop progressing for minutes at a time | The Mac goes to sleep and pauses everything | Run long sessions with `caffeinate -i make e2e` or keep the lid open |
| Traefik answers 404 for `api.` after changing services | A container from an old compose file still claims the same router name | `make up` removes orphans; or `docker ps` and remove the stray container |
| Image build hangs at `pnpm install` | Corepack waiting for a download prompt | `COREPACK_ENABLE_DOWNLOAD_PROMPT=0` (already set in `web.Dockerfile`) |
| Exchange container restarts or `unhealthy` | Read why | `docker logs prayog-exchange-1 2>&1 \| tail -50`; journal errors stop it on purpose (fail safe) |
| The book in the browser says `resyncing` | It missed a feed message (seq gap) | Nothing: it resubscribes and gets a fresh snapshot within a second |
