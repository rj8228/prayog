# Prayog

A simulated stock exchange and bot arena: simulated traders create the market; people trade by hand or with bots.

Status: a live market runs locally: an exchange with a journal and deterministic replay, simulated traders (a market
maker, noise and momentum traders), a web market page where you can watch and trade, and a Python SDK for bots.
Post-trade P&L, the leaderboard and the ops page are next. See [docs/PROGRESS.md](docs/PROGRESS.md).

- **Visit and trade:** [docs/guides/using-the-market.md](docs/guides/using-the-market.md)
- **Build a trading bot:** [docs/bots/](docs/bots/README.md)
- **Run, check and debug:** [docs/runbook/](docs/runbook/README.md)
- **Learn how it works:** [docs/learnings/](docs/learnings/README.md); the interactive pages are also published at https://rj8228.github.io/prayog/

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
make up     # builds and starts everything (exchange, traders, web, PostgreSQL, Kafka, Redis, Keycloak, Traefik)
make smoke  # 28 quick checks
make e2e    # checks the live market is correct: feeds, liquidity, a bot round trip, journal replay
make down   # stops it (data is kept); `make reset` also deletes the data volumes
```

| Address | What |
|---|---|
| http://app.prayog.localhost | The live market: watch without signing in; sign in as `trader1` to trade |
| http://api.prayog.localhost | The exchange API for bots ([reference](docs/bots/api.md)) |
| http://auth.prayog.localhost | Keycloak; admin console at `/admin` (credentials in `.env`) |
| http://traefik.prayog.localhost/dashboard/ | Traefik routes |
| `localhost:5432`, `localhost:9094`, `localhost:6379` | PostgreSQL, Kafka, Redis for tools on your machine |

Browsers, curl and Python resolve `*.localhost` to your own machine. If a tool doesn't, add to `/etc/hosts`:

```
127.0.0.1 app.prayog.localhost api.prayog.localhost auth.prayog.localhost traefik.prayog.localhost grafana.prayog.localhost
```

Step-by-step checks, failure drills and debugging help: [docs/runbook/](docs/runbook/README.md).

The Keycloak realm and the database users are created only when their data volumes are empty. After changing
`deploy/compose/keycloak/prayog-realm.json`, `deploy/compose/postgres/init/` or the secrets in `.env`, run `make reset`.
