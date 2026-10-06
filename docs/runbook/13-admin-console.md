# 13. Admin console

Sign in at http://app.prayog.localhost as **admin1** (`grep PRAYOG_ADMIN1_PASSWORD .env`). An **Admin** tab appears
in the header. admin1 also trades like any trader on the Market tab.

| Panel | Do | Expect |
|---|---|---|
| Market control | **Halt**, then **Open** | Session badge turns HALTED (new orders rejected, cancels allowed), then OPEN |
| | **Set speed** 10, then **Real time** | The sim clock runs 10x, then normal |
| | **Skip to next open** | Today's open orders expire; the next day opens |
| Simulated traders | **Volatile** / **Calm** | Wider spreads and bigger moves within seconds |
| | **News +3%** on INFY | INFY trades about 3% higher within a few seconds |
| | **Pause** / **Resume** | The market maker's quotes disappear and trading stops; then resumes |
| Self-test | **Run checks** | `7/7 checks passed`, including an online replay of the live journal and the Kafka publisher's lag |
| Health | Watch | Input seq rising, ring nearly empty, publish errors 0, latency p50 a few ms |
| Accounts | **Cancel all** / **Disable** / **Enable** | That account's orders cancelled; disabled accounts get `ACCOUNT_DISABLED` |
| Event replay | Pick a symbol, **Load from journal**, **Play** | The book and price replay exactly as they happened |

For any trader: click an order id in **My orders** (or a fill) to see its journey from the journal and the round trip
you measured.

If the Admin tab doesn't appear, the user lacks the `admin` role: run `make users`, sign out and in again.
