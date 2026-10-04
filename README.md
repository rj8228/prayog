# Prayog

A simulated stock exchange and bot arena: simulated traders create the market; people trade by hand or with bots.

Status: early development. See [docs/BUILD_PLAN.md](docs/BUILD_PLAN.md) and [docs/PROGRESS.md](docs/PROGRESS.md).

## Prerequisites

Java 21, Docker (Desktop with at least 4 GB of memory), [uv](https://docs.astral.sh/uv/), Node 22 with pnpm 10 (`corepack`).

## Quick start

```sh
make help   # list targets
make test   # build and test everything
```

## Local stack

```sh
make env    # once: creates .env with random local secrets (git-ignored)
make up     # starts PostgreSQL, Kafka, Redis, Keycloak and Traefik; returns when all are healthy
make smoke  # checks the running stack end to end
make down   # stops it (data is kept); `make reset` also deletes the data volumes
```

| Address | What |
|---|---|
| http://app.prayog.localhost | Web terminal (placeholder until S18) |
| http://api.prayog.localhost | Gateway (placeholder until S10) |
| http://auth.prayog.localhost | Keycloak; admin console at `/admin` (credentials in `.env`) |
| http://traefik.prayog.localhost/dashboard/ | Traefik routes |
| `localhost:5432`, `localhost:9094`, `localhost:6379` | PostgreSQL, Kafka, Redis for tools on your machine |

Browsers, curl and Python resolve `*.localhost` to your own machine. If a tool doesn't, add to `/etc/hosts`:

```
127.0.0.1 app.prayog.localhost api.prayog.localhost auth.prayog.localhost traefik.prayog.localhost grafana.prayog.localhost
```

The Keycloak realm and the database users are created only when their data volumes are empty. After changing
`deploy/compose/keycloak/prayog-realm.json`, `deploy/compose/postgres/init/` or the secrets in `.env`, run `make reset`.
