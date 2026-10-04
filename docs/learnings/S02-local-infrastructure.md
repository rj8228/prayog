# S2 Local infrastructure

**Built:** a Docker Compose stack (profile `infra`) with PostgreSQL, Kafka in KRaft mode, Redis, Keycloak with the `prayog` realm, and Traefik routing `*.prayog.localhost`. Every service is health-checked, `make up` waits until all are healthy, and `make smoke` proves the stack works end to end, locally and in CI. [ADR 0008](../adr/0008-local-infrastructure.md). Background pages: [kafka.html](../overview/kafka.html), [oauth-oidc.html](../overview/oauth-oidc.html), [compose-traefik.html](../overview/compose-traefik.html).

## Concepts

- [ ] **Containers and images.** An image is a frozen file system plus a start command; a container is a running copy. Pin exact tags so every machine runs the same thing.
- [ ] **Compose and profiles.** One file describes the whole stack; profiles (`infra`, `app`, `obs`) start only the parts you need.
- [ ] **Health vs. started.** A started database may not accept connections yet. Health checks ask the real question; `depends_on: service_healthy` and `up --wait` use the answer.
- [ ] **Named volumes.** Data outlives containers. Init scripts and realm imports run only on an empty volume, hence `make reset`.
- [ ] **Kafka in one paragraph.** An append-only log split into partitions; producers append, consumers read at their own offset; a consumer group shares partitions among its members.
- [ ] **KRaft.** Kafka manages its own metadata with a Raft quorum of controllers, so ZooKeeper is gone. Here one node is both broker and controller.
- [ ] **Advertised listeners.** A client bootstraps anywhere, then connects to the address the broker advertises. Each network (containers, host) needs its own listener advertising an address reachable from it.
- [ ] **Explicit topics.** Auto-create is off, so a typo can't create a topic; a one-shot job creates the real one.
- [ ] **OAuth2 vs. OpenID Connect.** OAuth2 grants access tokens to call an API; OIDC adds who the user is (ID token, discovery, standard claims).
- [ ] **Public client + PKCE.** A browser can't keep a secret; PKCE binds the authorization code to a one-time verifier only the app knows.
- [ ] **Confidential client + client credentials.** Bots authenticate as themselves with id + secret; no user, no browser.
- [ ] **Token claims that matter.** `iss` (who issued it, pinned by `KC_HOSTNAME`), `aud` (who it's for: `prayog-api`), `exp`, roles in `realm_access`.
- [ ] **Reverse proxy.** One public port; routing by host name; services opt in with labels.
- [ ] **Attack surface.** Ports bound to 127.0.0.1; the Docker socket is powerful even read-only; HTTP is acceptable only locally.
- [ ] **Secrets.** Generated per machine, git-ignored, injected as environment variables or import placeholders; CI makes throwaway ones.
- [ ] **Test the test.** Stopping Redis made the smoke test fail, which proves it can.
- [ ] **zsh again.** `$C` with spaces is one word in zsh: `docker compose stop redis` silently didn't run until written out in full.

## In an interview

> "`make up` starts the stack with Compose and waits on real health checks (pg_isready, a Kafka API call, Keycloak's readiness endpoint), then a smoke script verifies it end to end, down to minting a client-credentials token and checking its issuer, audience and roles. The same script runs in CI."

> "Kafka runs as a single KRaft node with two listeners, because a client connects to whatever address the broker advertises: containers get `kafka:9092`, the host gets `localhost:9094`."

## Explain-back answers

1. **Two Kafka listeners.** Clients bootstrap, then reconnect to the advertised address. Containers can resolve `kafka`, the host can't; so `INTERNAL` advertises `kafka:9092` and `EXTERNAL` advertises `localhost:9094`. Getting it wrong gives "connects, then times out".
2. **Health checks and `--wait`.** `depends_on` alone waits for start, not readiness. Health checks ask the real question per service; `service_healthy` orders Keycloak after Postgres and topic creation after Kafka; `up --wait` makes `make up` return only when all are healthy, so nothing races the stack.
3. **Public + PKCE vs. confidential.** The browser can't hold a secret, so it proves possession of a one-time verifier (PKCE). Bots run on machines their owners control, so they use client credentials. The audience mapper puts `aud: prayog-api` in every token, and `KC_HOSTNAME` pins `iss`, so the gateway can validate both.
4. **Secrets.** `make env` generates random values into a git-ignored `.env`; Compose fails fast if one is missing; the realm file holds `${...}` placeholders; CI generates throwaway values. Imports run once (`IGNORE_EXISTING`, initdb on empty volume), so changes need `make reset`.
5. **Traefik and security.** One entry point routes by `Host` to services that opt in with labels; unknown hosts get 404. Host ports bind to 127.0.0.1; the Docker socket is powerful even read-only; plain HTTP and `sslRequired: external` are for local use only.
