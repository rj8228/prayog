# 5. Data and messaging

Uses the `dc` helper from the [README](README.md).

## PostgreSQL

| Step | Command | Expect |
|---|---|---|
| List databases | `dc exec postgres psql -U prayog -d prayog -c '\l'` | `prayog` (owner prayog) and `keycloak` (owner keycloak) |
| Interactive shell | `dc exec postgres psql -U prayog -d prayog` | `prayog=#` prompt; `\q` to quit |
| From a desktop tool | host `localhost`, port `5432`, database `prayog`, user and password from `.env` | Connects (DBeaver, IntelliJ, TablePlus) |

There are no Prayog tables yet; post-trade adds them in S16.

### The post-trade ledger (S16)

Tables in the `prayog` database: `trades`, `fills`, `positions`, `marks`, `orders`, `rejections`,
`consumer_progress`, `account_names` ([ADR 0015](../adr/0015-post-trade-ledger-and-leaderboard.md)).

| Step | Command | Expect |
|---|---|---|
| Migrations ran | `dc logs post-trade-migrate` | `migrations applied: N, schema version 1` (0 on later starts) |
| Ledger totals | `curl -s -H "Authorization: Bearer $T" http://api.prayog.localhost/api/v1/post-trade/status \| jq` | `pnlBeforeCharges: 0`, every `netQuantityBySymbol` 0 |
| Biggest positions | `dc exec postgres psql -U prayog -d prayog -c "select account_id, symbol, quantity, realised_pnl/100.0 as realised from positions order by abs(quantity) desc limit 5"` | Rows; the market maker usually holds the most |
| Consumer lag | `dc exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 --describe --group prayog-post-trade` | `LAG` near 0 per partition |
| Rebuild from scratch | stop post-trade, `truncate trades, fills, positions, marks, orders, rejections, consumer_progress`, reset the group's offsets to earliest, start | The ledger rebuilds from the topic to the same totals |

## Redis

| Step | Command | Expect |
|---|---|---|
| Write | `dc exec redis redis-cli set hello world` | `OK` |
| Read | `dc exec redis redis-cli get hello` | `"world"` |
| Clean up | `dc exec redis redis-cli del hello` | `(integer) 1` |
| Leaderboard (S17) | `dc exec redis redis-cli zrevrange prayog:leaderboard 0 4 withscores` | Account ids with net P&L in paise, best first |
| Board size | `dc exec redis redis-cli zcard prayog:leaderboard` | Number of accounts that have traded |
| Rebuild drill | `dc exec redis redis-cli del prayog:leaderboard`, wait for the next trade | The board is rebuilt from PostgreSQL (post-trade log: `leaderboard rebuilt`) |

## Kafka

Use a scratch topic, never the real `prayog.exchange.events.v1`, so later services don't read test messages. Kafka 4.3 prints a notice about the new consumer rebalance protocol (KIP-848) when a console consumer starts; it's informational.

| Step | Command | Expect |
|---|---|---|
| Real topic | `dc exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --describe --topic prayog.exchange.events.v1` | `PartitionCount: 3` and three partition lines |
| Create scratch | `dc exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --create --topic scratch --partitions 3` | `Created topic scratch.` |
| Consume (terminal 1) | `dc exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092 --topic scratch --from-beginning --formatter-property print.key=true --formatter-property print.partition=true` | Waits; prints `Partition:N  key  value` per message |
| Produce (terminal 2) | `dc exec kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka:9092 --topic scratch` | Type lines; each appears in terminal 1. Ctrl-C both when done |
| Produce with keys | add `--reader-property parse.key=true --reader-property key.separator=:` and type `INFY:hello`, `INFY:again`, `TCS:x` | Both INFY messages show the same partition (verified: INFY on 0, TCS on 2) |
| Consumer groups | `dc exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 --list` | Groups (console consumers make `console-consumer-...`) |
| Delete scratch | `dc exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --delete --topic scratch` | No output |
| Auto-create is off | `echo hi \| dc exec -T kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka:9092 --topic does-not-exist --producer-property max.block.ms=5000` | After 5 s: `TimeoutException: Topic does-not-exist not present in metadata`; `--list` shows no such topic |

### The exchange's event stream (S15)

The exchange publishes every event to `prayog.exchange.events.v1` from its journal, on its own thread
([ADR 0013](../adr/0013-kafka-event-publisher.md)). `T` below is an ops token: `make ops-token` prints one.

| Step | Command | Expect |
|---|---|---|
| Publisher state | `curl -s -H "Authorization: Bearer $T" http://api.prayog.localhost/api/v1/ops/status \| jq .kafka` | `connected: true`, `lag` near 0, `errors` 0 |
| Read the stream | `dc exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092 --topic prayog.exchange.events.v1 --formatter-property print.key=true` | One JSON event per line, `{"type":"Trade","seq":...}`, key = symbol |
| Records per partition | `dc exec kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server kafka:9092 --topic prayog.exchange.events.v1` | `topic:0:N` for partitions 0-2; the sum is at least the published seq |
| Outage drill | `dc stop kafka`, wait 10 s, `dc up -d --wait kafka` | Trading continues; `lag` grows, then returns to ~0 within seconds |
| Checkpoint | `dc exec exchange cat /data/journal/kafka-publisher.checkpoint` | The published seq (saved once a second) |
| Full check | `uv run python tests/e2e/kafka_check.py` | `7/7 checks passed` (part of `make e2e`) |

After an outage some events reach the topic twice; that is expected (at-least-once). Consumers skip by event id
(`seq`, also the `eventId` header).

Explainer with a simulator: `open docs/overview/kafka.html`.
