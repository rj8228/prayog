# 8. Build and tests

| What | Command | Expect |
|---|---|---|
| Everything (Java, Python, web) | `make test` | `BUILD SUCCESS`, ruff `All checks passed!`, pytest and vitest passing |
| Java only | `make test-java` | `contracts` 49 tests, `exchange-core` 105 tests |
| One Java module | `./mvnw -pl services/exchange/exchange-core -am test` | Only that module and what it depends on |
| One Java test class | `./mvnw -pl services/exchange/exchange-core -am test -Dtest=FileJournalTest -Dsurefire.failIfNoSpecifiedTests=false` | Just that class |
| Deterministic replay check | `make replay-check` | `replay-check: 51001 commands, ~69000 events, sha256 ...` and `BUILD SUCCESS` |
| Stack acceptance | `make smoke` | `All checks passed.` (stack must be up) |
| Format code | `make fmt` | Rewrites Java, Python and web files to the project style |
| Lint without changing | `make lint` | Exit code 0; it prints each tool's command (Spotless, Ruff, ESLint) |

Notes:

- Write Maven arguments out in full in zsh (no `$ARGS` variables).
- `-am` builds the modules the target depends on (`contracts`); without it a single module can't find them.

## CI

Every push to `main` runs `.github/workflows/ci.yml`: Java (Maven), Python (uv), Web (pnpm), Replay determinism check,
Local stack smoke test. See them with:

```sh
gh run list --limit 5
gh run view <id> --log-failed     # only the failing steps' output
```
