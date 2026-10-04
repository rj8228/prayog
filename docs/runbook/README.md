# Runbook

Hands-on steps to run, check, explore and debug Prayog on your machine, grouped by purpose. Every command runs from the
repository root (`~/code/prayog`). Each step says what you should see, so you can tell at once whether something is wrong.

Last verified: 2026-10-04 on an Apple M1, 8 GB, Docker Desktop 29.8, with the live market running (exchange,
simulated traders, web app). Every command and expectation was run against the live stack.

| # | Bucket | Use it to |
|---|---|---|
| 1 | [Start and stop](01-start-and-stop.md) | Create secrets, start, stop, reset the local stack |
| 2 | [Health checks](02-health-checks.md) | Confirm everything works in one command; read service health |
| 3 | [Browser tour](03-browser-tour.md) | Click through every web address |
| 4 | [Identity and tokens](04-identity-and-tokens.md) | Sign in, get bot tokens, inspect them, use the Keycloak admin API |
| 5 | [Data and messaging](05-data-and-messaging.md) | Use PostgreSQL, Redis and Kafka directly |
| 6 | [Failure drills](06-failure-drills.md) | Break things on purpose and watch them recover |
| 7 | [Debugging](07-debugging.md) | Find out why something is down; known problems and fixes |
| 8 | [Build and tests](08-build-and-tests.md) | Run the test suites, the replay check, and read CI |
| 9 | [Learning pages](09-learning-pages.md) | The interactive explainers, ADRs and learnings |
| 10 | [Trading as a user](10-trading-as-a-user.md) | Watch the market, sign in, place and cancel orders |
| 11 | [Bots](11-bots.md) | Check the simulated traders and the sample bot; switch scenarios |
| 12 | [Exchange internals](12-exchange-internals.md) | Readiness, metrics, rejected orders, recovery, live journal replay |
| 13 | [Admin console](13-admin-console.md) | Market and simulation control, self-test, accounts, event replay |

## Addresses

| Address | What | Login |
|---|---|---|
| http://app.prayog.localhost | The live market: watch without login, sign in to trade | `trader1`, password in `.env` |
| http://api.prayog.localhost | The exchange API for bots ([reference](../bots/api.md)) | bot client id + secret |
| http://api.prayog.localhost/actuator/prometheus | Exchange metrics | none |
| http://auth.prayog.localhost/realms/prayog/account | Keycloak account page for traders | `trader1` or `ops1`, passwords in `.env` |
| http://auth.prayog.localhost/admin | Keycloak admin console (switch to realm **prayog**) | `KEYCLOAK_ADMIN` / `KEYCLOAK_ADMIN_PASSWORD` in `.env` |
| http://auth.prayog.localhost/realms/prayog/.well-known/openid-configuration | OIDC discovery document | none |
| http://traefik.prayog.localhost/dashboard/ | Traefik routers and services | none |
| `localhost:5432` | PostgreSQL (database `prayog`) | `POSTGRES_USER` / `POSTGRES_PASSWORD` in `.env` |
| `localhost:9094` | Kafka for tools on your machine | none |
| `localhost:6379` | Redis | none |

Use Chrome or Firefox; Safari is untested with `*.localhost` names (see [Debugging](07-debugging.md)).

## One-time shell helper

Most steps use a short `dc` command. Paste this into your terminal (or add it to `~/.zshrc`):

```sh
alias dc='docker compose --env-file .env -f deploy/compose/compose.yaml --profile infra --profile app'
```

To load the secrets into your shell for the curl examples:

```sh
set -a; source .env; set +a
```
