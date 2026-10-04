# 1. Start and stop

Prerequisite: Docker Desktop running (whale icon steady). Memory: at least 4 GB for Docker (Settings → Resources).

| Step | Command | Expect |
|---|---|---|
| Create secrets (once) | `make env` | `Created .env with random local secrets.` (or `.env already exists`) |
| Start | `make up` | Returns after every service is healthy: about 2.5 min the first time (image downloads), about 20 s after that |
| Status | `make ps` | Every service `Up ... (healthy)`; `kafka-init` shows `Exited (0)`, which is correct (one-off job) |
| Stop, keep data | `make down` | Containers and network removed; volumes kept |
| Stop and delete all local data | `make reset` | Also removes the `postgres-data`, `kafka-data` and `redis-data` volumes |

## When to use `make reset`

The Keycloak realm and the Postgres users are created only on empty volumes. After you change any of these, run
`make reset` and then `make up`:

- `deploy/compose/keycloak/prayog-realm.json`
- `deploy/compose/postgres/init/`
- a password or secret in `.env`

## Starting fresh on a new machine

```sh
git clone https://github.com/rj8228/prayog.git && cd prayog
make env && make up && make smoke
```
