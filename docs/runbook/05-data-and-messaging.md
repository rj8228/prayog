# 5. Data and messaging

Uses the `dc` helper from the [README](README.md).

## PostgreSQL

| Step | Command | Expect |
|---|---|---|
| List databases | `dc exec postgres psql -U prayog -d prayog -c '\l'` | `prayog` (owner prayog) and `keycloak` (owner keycloak) |
| Interactive shell | `dc exec postgres psql -U prayog -d prayog` | `prayog=#` prompt; `\q` to quit |
| From a desktop tool | host `localhost`, port `5432`, database `prayog`, user and password from `.env` | Connects (DBeaver, IntelliJ, TablePlus) |

There are no Prayog tables yet; post-trade adds them in S16.

## Redis

| Step | Command | Expect |
|---|---|---|
| Write | `dc exec redis redis-cli set hello world` | `OK` |
| Read | `dc exec redis redis-cli get hello` | `"world"` |
| Clean up | `dc exec redis redis-cli del hello` | `(integer) 1` |

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

Explainer with a simulator: `open docs/overview/kafka.html`.
