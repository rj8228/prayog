# 9. Learning pages

Open any page with `open docs/overview/<name>.html` (they work offline).

| Page | Topic | Try-it panel |
|---|---|---|
| `functional.html` | What the exchange does (NSE-style) | |
| `technical.html` | How it is built | |
| `ring-buffer.html` | LMAX Disruptor ring | 8-slot simulator |
| `journaling.html` | fsync, group commit, torn writes, replay | Crash simulator |
| `sbe.html` | Simple Binary Encoding | Live NewOrder encoder |
| `fix-protocol.html` | FIX messages and sessions | Builder and parser with checksum |
| `kafka.html` | Topics, partitions, consumer groups | Produce/consume/crash simulator |
| `oauth-oidc.html` | OAuth2, OIDC, PKCE, JWT | PKCE calculator, JWT decoder |
| `compose-traefik.html` | Compose, health checks, reverse proxy | Start-order and routing demos |
| `market-data-feeds.html` | Snapshots, deltas, sequence numbers, gap recovery | Feed simulator with dropped messages |
| `market-making.html` | Spread, inventory skew, self-trade traps, reconciliation | Market-maker simulator |
| `java-memory-model.html` | Happens-before, volatile, false sharing | Litmus test |
| `java-off-heap.html` | Buffers, Unsafe, VarHandle, FFM | Endianness explorer |
| `java-gc-jit.html` | GC, JIT, latency measurement | Tail-latency simulator |
| `modern-java.html` | Records, sealed types, pattern matching | Exhaustiveness checker |

Also:

- `docs/learnings/README.md`: one file per session with concepts to tick off and explain-back answers.
- `docs/adr/`: every decision and why (0001 to 0008).
- `docs/PROGRESS.md`: what's done and what's next.
