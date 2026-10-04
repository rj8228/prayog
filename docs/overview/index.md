# Interactive pages

Self-contained explainers, each with a business example, the mechanics, a try-it panel and how Prayog uses it. They
open full-page; use **← Learnings** at the top of each one (or your browser's back button) to come back. From a clone
they also work offline: `open docs/overview/<name>.html`.

## Overviews

<div class="grid cards pages" markdown>

-   [Functional overview](functional.html)

    What a person can do on Prayog, what the exchange does on its own, and when each thing happens.

-   [Technical overview](technical.html)

    How each feature is built, how an order flows through the system, and what a production exchange does differently.

</div>

## Trading infrastructure

<div class="grid cards pages" markdown>

-   [Ring buffer](ring-buffer.html)

    Turning thousands of concurrent orders into one fair, replayable sequence without a single lock.

-   [Journaling](journaling.html)

    Making every acknowledged order survive a crash: fsync, write-ahead logs, group commit, checksums and replay.

-   [SBE](sbe.html)

    Simple Binary Encoding, the FIX binary format Prayog uses for its journal: the idea, then the bytes.

-   [FIX protocol](fix-protocol.html)

    How brokers and exchanges exchange orders and fills: message format, order lifecycle and sessions.

-   [Market data feeds](market-data-feeds.html)

    Keeping thousands of client copies of the order book exactly right, and detecting when one isn't.

-   [Market making](market-making.html)

    Quoting both sides for the spread: the economics, the risks, and two bugs in Prayog's own market maker.

</div>

## Platform

<div class="grid cards pages" markdown>

-   [Kafka](kafka.html)

    A durable, replayable log that carries every trade to downstream services without slowing the exchange.

-   [OAuth2 and OIDC](oauth-oidc.html)

    How the gateway knows who is calling, via signed Keycloak tokens, PKCE and token validation.

-   [Compose and Traefik](compose-traefik.html)

    How one YAML file becomes a running platform, with a reverse proxy giving every service a friendly name.

</div>

## Core Java

<div class="grid cards pages" markdown>

-   [Java Memory Model](java-memory-model.html)

    When a write on one thread is guaranteed visible to another: the basis of volatile, atomics and the Disruptor.

-   [Off-heap memory](java-off-heap.html)

    Working with raw bytes: ByteBuffer, Unsafe, the Foreign Memory API and the flyweight pattern.

-   [GC and JIT](java-gc-jit.html)

    Garbage collection pauses and JIT warm-up, and how to keep both off the order path.

-   [Modern Java features](modern-java.html)

    Records, sealed interfaces and exhaustive pattern-matching switch, as used for Prayog's commands and events.

</div>
