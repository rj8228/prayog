---
title: Home
description: Prayog is a simulated stock exchange and bot arena. Simulated traders create the market; people trade by hand or with bots.
hide:
  - navigation
  - toc
---

# Prayog

**A simulated stock exchange and bot arena.** Simulated traders create the market; people trade on it by hand in the
browser or with their own bots, against a real matching engine with a journal and deterministic replay.

<div class="grid cards quick" markdown>

-   :material-finance:{ .lg } **Visit the market**

    Watch the live order book and trades, sign in, place and cancel orders.

    [:octicons-arrow-right-24: Using the market](guides/using-the-market.md)

-   :material-robot-outline:{ .lg } **Build a bot**

    Get a token, read the feeds, send orders with the Python SDK.

    [:octicons-arrow-right-24: Bot builder guide](bots/README.md) · [API](bots/api.md)

-   :material-console:{ .lg } **Run & debug**

    Start the stack, check its health, break it on purpose, find out why.

    [:octicons-arrow-right-24: Runbook](runbook/README.md)

-   :material-school-outline:{ .lg } **Learn how it works**

    Session notes, decisions and 15 interactive explainers with simulators.

    [:octicons-arrow-right-24: Learnings](learnings/README.md) · [Interactive pages](overview/index.md)

</div>

## How it fits together

Click any box to read the page that explains it.

<!-- Links in the SVG use xlink:href: Material's instant navigation rewrites every [href] element and fails on SVG links. -->
<figure class="arch" markdown="0">
<svg xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 520 790" role="group" aria-label="Prayog architecture: clients reach the exchange through Traefik; the exchange runs gateway, ring buffer, matching engine, journal and outbound feeds">
  <defs>
    <marker id="arch-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
      <path class="arch-head" d="M0 0 L10 5 L0 10 z"/>
    </marker>
  </defs>

  <!-- Clients -->
  <a xlink:href="guides/using-the-market/"><title>Using the market</title>
    <rect class="arch-node" x="8" y="8" width="160" height="58" rx="10"/>
    <text class="arch-title" x="88" y="32">Browser</text>
    <text class="arch-sub" x="88" y="52">web market page</text></a>
  <a xlink:href="bots/"><title>Bot builder guide</title>
    <rect class="arch-node" x="180" y="8" width="160" height="58" rx="10"/>
    <text class="arch-title" x="260" y="32">Your bot</text>
    <text class="arch-sub" x="260" y="52">Python SDK</text></a>
  <a xlink:href="overview/market-making.html"><title>Market making (interactive)</title>
    <rect class="arch-node" x="352" y="8" width="160" height="58" rx="10"/>
    <text class="arch-title" x="432" y="32">Simulated traders</text>
    <text class="arch-sub" x="432" y="52">market maker, noise</text></a>

  <!-- Edge services -->
  <a xlink:href="overview/compose-traefik.html"><title>Compose and Traefik (interactive)</title>
    <rect class="arch-node" x="8" y="102" width="250" height="50" rx="10"/>
    <text class="arch-title" x="133" y="124">Traefik</text>
    <text class="arch-sub" x="133" y="142">reverse proxy, *.prayog.localhost</text></a>
  <a xlink:href="overview/oauth-oidc.html"><title>OAuth2 and OIDC (interactive)</title>
    <rect class="arch-node" x="270" y="102" width="140" height="50" rx="10"/>
    <text class="arch-title" x="340" y="124">Keycloak</text>
    <text class="arch-sub" x="340" y="142">sign-in, JWT</text></a>

  <path class="arch-edge" d="M88 66 V100" marker-end="url(#arch-arrow)"/>
  <path class="arch-edge" d="M220 66 V100" marker-end="url(#arch-arrow)"/>

  <!-- Exchange service -->
  <rect class="arch-group" x="8" y="186" width="504" height="460" rx="14"/>
  <a xlink:href="overview/technical.html"><title>Technical overview (interactive)</title>
    <text class="arch-group-title" x="500" y="636" text-anchor="end">Exchange service · one JVM, one shard ↗</text></a>

  <path class="arch-edge" d="M133 152 V224" marker-end="url(#arch-arrow)"/>
  <path class="arch-edge arch-dashed" d="M330 152 L292 224" marker-end="url(#arch-arrow)"/>
  <text class="arch-note" x="352" y="186" style="text-anchor: start">JWT keys</text>
  <path class="arch-edge" d="M470 66 V252 H322" marker-end="url(#arch-arrow)"/>
  <text class="arch-note" x="478" y="164" text-anchor="middle" transform="rotate(90 478 164)">direct, inside Docker</text>

  <a xlink:href="learnings/S10-S13-live-market/"><title>S10 to S13: the live market</title>
    <rect class="arch-node" x="28" y="226" width="290" height="52" rx="10"/>
    <text class="arch-title" x="173" y="248">Gateway</text>
    <text class="arch-sub" x="173" y="267">REST + WebSocket, JWT, rate limits</text></a>
  <path class="arch-edge" d="M173 278 V302" marker-end="url(#arch-arrow)"/>

  <a xlink:href="overview/ring-buffer.html"><title>Ring buffer (interactive)</title>
    <rect class="arch-node arch-hot" x="28" y="304" width="290" height="52" rx="10"/>
    <text class="arch-title" x="173" y="326">Ring buffer</text>
    <text class="arch-sub" x="173" y="345">LMAX Disruptor, one sequence</text></a>
  <path class="arch-edge" d="M173 356 V380" marker-end="url(#arch-arrow)"/>

  <a xlink:href="learnings/S03-S04-contracts-and-order-book/"><title>S3 to S4: contracts and the order book</title>
    <rect class="arch-node arch-hot" x="28" y="382" width="290" height="52" rx="10"/>
    <text class="arch-title" x="173" y="404">Matching engine</text>
    <text class="arch-sub" x="173" y="423">single writer, price-time priority</text></a>
  <path class="arch-edge" d="M173 434 V458" marker-end="url(#arch-arrow)"/>

  <a xlink:href="overview/journaling.html"><title>Journaling (interactive)</title>
    <rect class="arch-node arch-hot" x="28" y="460" width="290" height="52" rx="10"/>
    <text class="arch-title" x="173" y="482">Journal</text>
    <text class="arch-sub" x="173" y="501">write-ahead, fsync per batch</text></a>
  <path class="arch-edge" d="M120 512 V546" marker-end="url(#arch-arrow)"/>
  <path class="arch-edge" d="M226 512 V546" marker-end="url(#arch-arrow)"/>
  <text class="arch-note" x="173" y="534">outbound</text>

  <a xlink:href="overview/market-data-feeds.html"><title>Market data feeds (interactive)</title>
    <rect class="arch-node" x="28" y="548" width="140" height="52" rx="10"/>
    <text class="arch-title" x="98" y="570">Market data</text>
    <text class="arch-sub" x="98" y="589">snapshot + deltas</text></a>
  <a xlink:href="adr/0009-exchange-service-gateway-market-data/"><title>ADR 0009: gateway, market data and recovery</title>
    <rect class="arch-node" x="178" y="548" width="140" height="52" rx="10"/>
    <text class="arch-title" x="248" y="570">Private feed</text>
    <text class="arch-sub" x="248" y="589">your orders, fills</text></a>
  <text class="arch-note" x="110" y="618" style="text-anchor: start">to browsers and bots over WebSocket</text>

  <!-- Helpers on the right -->
  <a xlink:href="learnings/S07-sequencer-pipeline/"><title>S7: sequencer pipeline and simulated clock</title>
    <rect class="arch-node" x="340" y="304" width="112" height="52" rx="10"/>
    <text class="arch-title" x="396" y="326">Clock</text>
    <text class="arch-sub" x="396" y="345">time is an input</text></a>
  <path class="arch-edge" d="M340 330 H320" marker-end="url(#arch-arrow)"/>
  <a xlink:href="overview/sbe.html"><title>SBE (interactive)</title>
    <rect class="arch-node" x="340" y="460" width="112" height="52" rx="10"/>
    <text class="arch-title" x="396" y="482">SBE codec</text>
    <text class="arch-sub" x="396" y="501">binary records</text></a>
  <path class="arch-edge arch-dashed" d="M340 486 H320"/>
  <a xlink:href="learnings/S08-journal-and-replay/"><title>S8: journal and deterministic replay</title>
    <rect class="arch-node" x="340" y="548" width="112" height="52" rx="10"/>
    <text class="arch-title" x="396" y="570">Replay</text>
    <text class="arch-sub" x="396" y="589">same bytes out</text></a>
  <path class="arch-edge arch-dashed" d="M396 548 V512"/>

  <!-- After the trade -->
  <rect class="arch-group" x="8" y="676" width="504" height="106" rx="14"/>
  <a xlink:href="adr/0015-post-trade-ledger-and-leaderboard/"><title>ADR 0015: post-trade ledger and leaderboard</title>
    <text class="arch-group-title" x="500" y="698" text-anchor="end">After the trade · post-trade service ↗</text></a>
  <path class="arch-edge" d="M60 600 V710" marker-end="url(#arch-arrow)"/>
  <text class="arch-note" x="66" y="664" style="text-anchor: start">journaled events</text>
  <a xlink:href="overview/kafka.html"><title>Kafka (interactive)</title>
    <rect class="arch-node" x="24" y="712" width="148" height="56" rx="10"/>
    <text class="arch-title" x="98" y="736">Kafka</text>
    <text class="arch-sub" x="98" y="755">event stream</text></a>
  <a xlink:href="learnings/S02-local-infrastructure/"><title>S2: local infrastructure</title>
    <rect class="arch-node" x="186" y="712" width="148" height="56" rx="10"/>
    <text class="arch-title" x="260" y="736">PostgreSQL</text>
    <text class="arch-sub" x="260" y="755">ledger, P&amp;L</text></a>
  <a xlink:href="runbook/05-data-and-messaging/"><title>Runbook: data and messaging</title>
    <rect class="arch-node" x="348" y="712" width="148" height="56" rx="10"/>
    <text class="arch-title" x="422" y="736">Redis</text>
    <text class="arch-sub" x="422" y="755">leaderboard</text></a>
</svg>
</figure>

## Learning path

The project in the order it was built. Each step has session notes (concepts to tick off, explain-back answers) and,
where one exists, an interactive page with a simulator.

<div class="grid cards path" markdown>

-   **1 · S1 Repo skeleton**

    Maven modules, wrappers, lock files, CI.

    [:material-notebook-outline: Notes](learnings/S01-repo-skeleton.md) ·
    [:material-scale-balance: ADR 0001](adr/0001-spring-boot-4-and-maven.md)

-   **2 · S3–S4 Contracts and order book**

    Integer money, price-time priority, property tests.

    [:material-notebook-outline: Notes](learnings/S03-S04-contracts-and-order-book.md) ·
    [:material-play-circle-outline: Modern Java](overview/modern-java.html)

-   **3 · S5 Market, cancel, modify**

    Commands, event sourcing, queue priority.

    [:material-notebook-outline: Notes](learnings/S05-market-cancel-modify.md) ·
    [:material-scale-balance: ADR 0004](adr/0004-commands-market-cancel-modify.md)

-   **4 · S6 Bands, sessions, STP**

    Price bands, halts, self-trade prevention, kill switch.

    [:material-notebook-outline: Notes](learnings/S06-bands-sessions-stp.md) ·
    [:material-play-circle-outline: Functional overview](overview/functional.html)

-   **5 · S7 Sequencer pipeline**

    Single writer, back-pressure, simulated clock.

    [:material-notebook-outline: Notes](learnings/S07-sequencer-pipeline.md) ·
    [:material-play-circle-outline: Ring buffer](overview/ring-buffer.html) ·
    [:material-play-circle-outline: Memory model](overview/java-memory-model.html) ·
    [:material-play-circle-outline: GC and JIT](overview/java-gc-jit.html)

-   **6 · S8 Journal and replay**

    fsync, group commit, torn writes, SBE, checksums.

    [:material-notebook-outline: Notes](learnings/S08-journal-and-replay.md) ·
    [:material-play-circle-outline: Journaling](overview/journaling.html) ·
    [:material-play-circle-outline: SBE](overview/sbe.html) ·
    [:material-play-circle-outline: Off-heap](overview/java-off-heap.html) ·
    [:material-play-circle-outline: FIX](overview/fix-protocol.html)

-   **7 · S2 Local infrastructure**

    Compose, Kafka KRaft, Keycloak, Traefik, secrets.

    [:material-notebook-outline: Notes](learnings/S02-local-infrastructure.md) ·
    [:material-play-circle-outline: Compose and Traefik](overview/compose-traefik.html) ·
    [:material-play-circle-outline: Kafka](overview/kafka.html) ·
    [:material-play-circle-outline: OAuth2 and OIDC](overview/oauth-oidc.html)

-   **8 · S10–S13 The live market**

    Gateway, feeds and gaps, SDK, market making, end-to-end checks.

    [:material-notebook-outline: Notes](learnings/S10-S13-live-market.md) ·
    [:material-play-circle-outline: Market data feeds](overview/market-data-feeds.html) ·
    [:material-play-circle-outline: Market making](overview/market-making.html) ·
    [:material-play-circle-outline: Technical overview](overview/technical.html)

-   **9 · Step 1.5 Admin, workspace, strategies**

    Admin console, draggable workspace, six browser strategies with risk limits.

    [:material-notebook-outline: Notes](learnings/step-1.5-admin-workspace-strategies.md) ·
    [:material-scale-balance: ADR 0012](adr/0012-trading-workspace-and-strategies.md)

-   **10 · S15 Kafka publisher**

    Outbox pattern: tail the durable log, checkpoint, at-least-once.

    [:material-notebook-outline: Notes](learnings/S15-kafka-publisher.md) ·
    [:material-play-circle-outline: Kafka](overview/kafka.html) ·
    [:material-scale-balance: ADR 0013](adr/0013-kafka-event-publisher.md)

-   **11 · S16–S17 Post-trade and leaderboard**

    Idempotent consumer, average-cost P&L, zero-sum checks, Redis sorted sets.

    [:material-notebook-outline: Notes](learnings/S16-S17-post-trade-and-leaderboard.md) ·
    [:material-scale-balance: ADR 0015](adr/0015-post-trade-ledger-and-leaderboard.md)

-   **12 · S18–S19 Terminal, blotter, ops page**

    Server-backed panels, official P&L, a whole simulated day from the browser.

    [:material-notebook-outline: Notes](learnings/S18-S19-terminal-blotter-ops.md) ·
    [:material-scale-balance: ADR 0017](adr/0017-ops-page-and-server-backed-panels.md)

-   **13 · Step 2 Recovery hardening**

    Duplicate client order IDs, rule versions, snapshots, journal archiving.

    [:material-notebook-outline: IDs](learnings/step-2-duplicate-client-order-ids.md) ·
    [:material-notebook-outline: Snapshots](learnings/step-2-engine-snapshots.md) ·
    [:material-notebook-outline: Archiving](learnings/step-2-journal-archiving.md)

-   **14 · S9 Benchmarks, allocation and GC**

    JMH, HdrHistogram, coordinated omission, JFR, GC settings, a boxing-free index.

    [:material-notebook-outline: S9](learnings/S09-benchmarks.md) ·
    [:material-notebook-outline: Allocation](learnings/step-3-allocation-and-gc.md) ·
    [:material-speedometer: Numbers](benchmarks.md)

-   **15 · S14 Realism checks**

    Do simulated prices look like a real market? Spreads, tails, clustering.

    [:material-notebook-outline: Notes](learnings/S14-realism.md) ·
    [:material-chart-line: Report](realism.md)

-   **16 · S20–S22 Rules, observability, docs**

    Cucumber scenarios, Prometheus and Grafana, failure modes, the demo.

    [:material-notebook-outline: S20](learnings/S20-rules-as-scenarios.md) ·
    [:material-notebook-outline: S21](learnings/S21-observability.md) ·
    [:material-notebook-outline: S22](learnings/S22-docs-and-demo.md) ·
    [:material-alert-outline: Failure modes](failure-modes.md)

</div>

Background: [industry context](learnings/industry-context.md) (exchange vs trading firm, what production engines do
differently). Every decision: [Decisions](adr/index.md). What's done and next: [Progress](PROGRESS.md).

## Run it locally

You need Java 21, Docker (Desktop with at least 4 GB of memory), [uv](https://docs.astral.sh/uv/), and Node 22 with
pnpm 10 (`corepack`).

```sh
make env    # once: creates .env with random local secrets (git-ignored)
make up     # builds and starts everything; add PROFILES="infra app obs" for Prometheus and Grafana
make smoke  # quick checks
make e2e    # the live market is correct: feeds, liquidity, bots, Kafka outage, post-trade, journal replay
make down   # stops it (data is kept); `make reset` also deletes the data volumes
make test   # build and test everything
```

| Address | What |
|---|---|
| http://app.prayog.localhost | The live market: watch without signing in; sign in as `trader1` to trade, `ops1` for the ops page |
| http://api.prayog.localhost | The exchange and post-trade API for bots ([reference](bots/api.md)) |
| http://grafana.prayog.localhost | Dashboards (profile `obs`) |
| http://auth.prayog.localhost | Keycloak; admin console at `/admin` (credentials in `.env`) |
| http://traefik.prayog.localhost/dashboard/ | Traefik routes |

Step-by-step checks, failure drills and debugging help are in the [runbook](runbook/README.md); start with
[Start and stop](runbook/01-start-and-stop.md).
