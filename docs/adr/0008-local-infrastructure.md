# 8. Local infrastructure with Docker Compose

Date: 2026-10-04

## Status

Accepted

## Context

S2 starts M3: a local stack of PostgreSQL, Kafka (KRaft), Redis, Keycloak and Traefik, with health checks and start order. *Done when* `make up` brings everything up healthy and the subdomains respond (BUILD_PLAN 10, 16.2 #18, #21, #24, #26 to #28). The development machine is an 8 GB Apple M1 with Docker Desktop limited to 3.8 GiB.

## Decisions

1. **One Compose file, `deploy/compose/compose.yaml`, project name `prayog`, profiles `infra`, `app`, `obs`.** S2 fills `infra`. The Makefile is the interface: `make env`, `up`, `down`, `ps`, `logs`, `smoke`, `reset`.
2. **Pinned images:** `postgres:16.15-alpine`, `redis:7.4.11-alpine`, `apache/kafka:4.3.1`, `quay.io/keycloak/keycloak:26.8.0`, `traefik:v3.7.13`, `traefik/whoami:v1.12.0` (latest patch releases on 2026-10-04).
3. **Readiness, not just start order.** Every long-running service has a health check that asks the real question (`pg_isready`; a Kafka API call; Keycloak `/health/ready` on management port 9000 through bash `/dev/tcp`, since the image has no curl; `redis-cli ping`; Traefik `--ping`). `depends_on: condition: service_healthy` orders Keycloak after PostgreSQL and topic creation after Kafka. `make up` uses `up --wait`, so it returns only when everything is healthy.
4. **Kafka: one KRaft node (broker + controller), fixed `CLUSTER_ID`.** Listeners: `INTERNAL` advertised as `kafka:9092` for containers, `EXTERNAL` advertised as `localhost:9094` for the host, `CONTROLLER` on 9093. Auto topic creation is off; a one-shot `kafka-init` job creates `prayog.exchange.events.v1` with 3 partitions. Internal topics use replication factor 1 (single node).
5. **Keycloak in `start-dev` mode, stored in PostgreSQL** (its own `keycloak` database and user, created by an init script). `KC_HOSTNAME=http://auth.prayog.localhost` fixes the token issuer; `KC_PROXY_HEADERS=xforwarded` because Traefik sits in front.
6. **Realm `prayog` imported from `deploy/compose/keycloak/prayog-realm.json`:**
   - `prayog-web`: public client, authorization code + PKCE (S256), redirects only to `app.prayog.localhost`.
   - `prayog-api`: no login flows; it exists to be the token audience.
   - `prayog-bot-demo`, `prayog-agents`: confidential clients, client credentials only; their service accounts have role `bot`.
   - Audience mapper on the three token-issuing clients: every token carries `aud: prayog-api`.
   - Realm roles `trader`, `ops`, `bot`; users `trader1` (trader) and `ops1` (ops) with full profiles, so login needs no extra step.
   - `sslRequired: external`: HTTP is allowed only from loopback and private addresses.
7. **Secrets never committed.** `make env` creates `.env` from `.env.example`, replacing every `change-me` with `openssl rand -hex 16`. Compose refuses to start with a missing value (`${VAR:?}`). The realm file holds `${...}` placeholders that Keycloak substitutes at import. CI generates its own throwaway `.env`.
8. **Traefik v3 as the single entry point on `127.0.0.1:80`,** routing by host name from container labels (`exposedbydefault=false`). `app.` and `api.` point at `traefik/whoami` placeholders until S18 and S10. `grafana.` comes with the `obs` profile (S21).
9. **Every host port binds to 127.0.0.1** (80, 5432, 6379, 9094): nothing is reachable from the local network.
10. **Memory caps:** Kafka and Keycloak heaps at 256 to 512 MB. Measured total for the infra profile: about 1.1 GB (Keycloak 570 MB, Kafka 410 MB, the rest under 50 MB each).
11. **Acceptance is a script, `make smoke`** (`deploy/compose/smoke.sh`), run locally and by a new CI job (`make env`, `make up`, `make smoke`, logs on failure, `make down`).

## Testing

- `make smoke`: 20 checks. Every subdomain through Traefik (and a 404 for an unknown host); the discovery document's issuer; client-credentials tokens for both bot clients with the right issuer, audience and `bot` role; a wrong secret refused with 401; `trader1` and `ops1` exist with their roles; `prayog-web` uses PKCE S256; PostgreSQL `SELECT 1` and the `keycloak` database's owner; Redis `PING`; the Kafka topic has 3 partitions; the host listener answers; auto topic creation is off.
- The check can fail: with Redis stopped it reports one failure and exits 1.
- A browser login as `trader1` through Traefik reaches the account page without extra prompts.
- Restart: `make down` then `make up` takes 22 s; data is kept, the realm import is skipped, and smoke passes.

## Trade-offs

- **Imports happen once.** Keycloak imports with `IGNORE_EXISTING` and PostgreSQL runs its init scripts only on an empty volume, so realm or secret changes need `make reset`. The alternative (re-import on every start) would wipe changes made in the admin console.
- **The Docker socket** is mounted read-only into Traefik. Read access still exposes every container's configuration (including environment secrets). Acceptable on a dev machine; a hosted setup should use a socket proxy or file-based routes.
- **Plain HTTP and `start-dev`.** Hosting needs TLS, Keycloak `start` mode and a real hostname.
- **Single-node Kafka** has no redundancy; good enough for a local exchange, and the topic's 3 partitions keep the consumer design honest.
- Python and curl resolve `*.localhost` without `/etc/hosts` on macOS; Safari was not tested, so the README lists the hosts line.
