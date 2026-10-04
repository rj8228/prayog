# 9. Learning pages

All the docs (runbook, guides, learnings, ADRs and these interactive pages) are published as one site at
https://rj8228.github.io/prayog/ by `.github/workflows/pages.yml` on every push to `main` that changes `docs/`,
`mkdocs.yml` or `README.md`. The interactive pages also work offline: `open docs/overview/<name>.html`.

Build and preview the site locally:

```sh
make docs        # strict MkDocs build into site/, then a check of every link in the built site
make docs-serve  # live preview at http://127.0.0.1:8000/prayog/
```

What you should see: `make docs` ends with `checked N internal links in M HTML files: 0 problems`. A broken link
between docs fails the build; a link from a doc to a Markdown file on github.com fails the link check (link the doc
relatively instead). Links to source code outside `docs/` use full `https://github.com/rj8228/prayog/blob/main/...` URLs.

| Page | Topic | Try-it panel |
|---|---|---|
| [`functional.html`](../overview/functional.html) | What the exchange does (NSE-style) | |
| [`technical.html`](../overview/technical.html) | How it is built | |
| [`ring-buffer.html`](../overview/ring-buffer.html) | LMAX Disruptor ring | 8-slot simulator |
| [`journaling.html`](../overview/journaling.html) | fsync, group commit, torn writes, replay | Crash simulator |
| [`sbe.html`](../overview/sbe.html) | Simple Binary Encoding | Live NewOrder encoder |
| [`fix-protocol.html`](../overview/fix-protocol.html) | FIX messages and sessions | Builder and parser with checksum |
| [`kafka.html`](../overview/kafka.html) | Topics, partitions, consumer groups | Produce/consume/crash simulator |
| [`oauth-oidc.html`](../overview/oauth-oidc.html) | OAuth2, OIDC, PKCE, JWT | PKCE calculator, JWT decoder |
| [`compose-traefik.html`](../overview/compose-traefik.html) | Compose, health checks, reverse proxy | Start-order and routing demos |
| [`market-data-feeds.html`](../overview/market-data-feeds.html) | Snapshots, deltas, sequence numbers, gap recovery | Feed simulator with dropped messages |
| [`market-making.html`](../overview/market-making.html) | Spread, inventory skew, self-trade traps, reconciliation | Market-maker simulator |
| [`java-memory-model.html`](../overview/java-memory-model.html) | Happens-before, volatile, false sharing | Litmus test |
| [`java-off-heap.html`](../overview/java-off-heap.html) | Buffers, Unsafe, VarHandle, FFM | Endianness explorer |
| [`java-gc-jit.html`](../overview/java-gc-jit.html) | GC, JIT, latency measurement | Tail-latency simulator |
| [`modern-java.html`](../overview/modern-java.html) | Records, sealed types, pattern matching | Exhaustiveness checker |

Also:

- [Learnings](../learnings/README.md): one file per session with concepts to tick off and explain-back answers.
- [Decisions](../adr/index.md): every decision and why.
- [Progress](../PROGRESS.md): what's done and what's next.
