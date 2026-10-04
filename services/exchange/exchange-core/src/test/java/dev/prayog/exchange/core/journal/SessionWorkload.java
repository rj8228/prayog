package dev.prayog.exchange.core.journal;

import dev.prayog.contracts.OrderType;
import dev.prayog.contracts.SessionState;
import dev.prayog.contracts.Side;
import dev.prayog.contracts.event.ExchangeEvent;
import dev.prayog.contracts.event.OrderAccepted;
import dev.prayog.exchange.core.CancelOrder;
import dev.prayog.exchange.core.ClockTick;
import dev.prayog.exchange.core.Command;
import dev.prayog.exchange.core.Instrument;
import dev.prayog.exchange.core.ModifyOrder;
import dev.prayog.exchange.core.NewOrder;
import dev.prayog.exchange.core.SessionSchedule;
import dev.prayog.exchange.core.SetAccountEnabled;
import dev.prayog.exchange.core.SetSessionState;
import dev.prayog.exchange.core.pipeline.CommandSlot;
import dev.prayog.exchange.core.pipeline.ExchangePipeline;
import dev.prayog.exchange.core.pipeline.PipelineHandler;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

/**
 * A busy trading session for replay tests: several gateway-like threads and a clock thread submit at the same time,
 * so the recorded order differs on every run. Covers every command type, both sides, cancels and modifies of real
 * order ids, a halt, disabled accounts, and clock ticks that cross the close and the next open (expiring orders).
 */
final class SessionWorkload {

    static final List<Instrument> INSTRUMENTS =
            List.of(new Instrument("INFY", 5, 1_000, 10_000, 10), new Instrument("TCS", 10, 500, 40_000, 5));
    static final SessionSchedule SCHEDULE =
            new SessionSchedule(LocalTime.of(9, 15), LocalTime.of(15, 30), ZoneOffset.ofHoursMinutes(5, 30));
    static final EngineSetup SETUP = new EngineSetup(INSTRUMENTS, SCHEDULE);

    /** 2026-10-05 09:00 IST, in epoch microseconds: 15 minutes before the open. */
    static final long START = LocalDateTime.of(2026, 10, 5, 9, 0).toEpochSecond(SCHEDULE.offset()) * 1_000_000L;

    private static final long THIRTY_SECONDS = 30_000_000L;
    private static final long HOUR = 3_600_000_000L;

    private SessionWorkload() {}

    /** An order the workload knows it owns. */
    record Known(String symbol, long orderId) {}

    /**
     * A later pipeline stage that plays the gateway's acknowledgements: it tells each account the ids of its accepted
     * orders, so the workload can cancel and modify orders it really owns, as a client would.
     */
    static final class Acks implements PipelineHandler {
        private final Map<Long, List<Known>> byAccount = new ConcurrentHashMap<>();

        @Override
        public void onSlot(CommandSlot slot, boolean endOfBatch) {
            for (ExchangeEvent event : slot.events()) {
                if (event instanceof OrderAccepted a) {
                    List<Known> known = byAccount.computeIfAbsent(a.accountId(), k -> new ArrayList<>());
                    synchronized (known) {
                        known.add(new Known(a.symbol(), a.orderId()));
                    }
                }
            }
        }

        /** One of the account's 50 most recent accepted orders, or null if it has none yet. */
        Known recent(long account, Random random) {
            List<Known> known = byAccount.get(account);
            if (known == null) {
                return null;
            }
            synchronized (known) {
                int size = known.size();
                return size == 0 ? null : known.get(size - 1 - random.nextInt(Math.min(size, 50)));
            }
        }
    }

    /** Submits {@code threads × perThread} trading commands plus clock ticks, and waits until all are submitted. */
    static void run(ExchangePipeline pipeline, Acks acks, int threads, int perThread) throws InterruptedException {
        pipeline.submit(new ClockTick(START));
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> all = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int id = t;
            all.add(new Thread(() -> {
                Random random = new Random(id);
                await(go);
                for (int i = 0; i < perThread; i++) {
                    pipeline.submit(command(random, acks, id, i));
                }
            }));
        }
        // The clock: about one tick per 50 trading commands, 30 sim seconds each. It runs from 09:00 to 16:00 (open at
        // 09:15, close and expiry at 15:30), then skips the night to 09:00 the next day, so the run crosses an open, a
        // close and the next open while the market is open most of the time.
        int ticks = threads * perThread / 50;
        all.add(new Thread(() -> {
            await(go);
            long time = START;
            long dayEnd = START + 7 * HOUR;
            for (int i = 1; i <= ticks; i++) {
                time += THIRTY_SECONDS;
                if (time >= dayEnd) {
                    time += 17 * HOUR;
                    dayEnd += 24 * HOUR;
                }
                pipeline.submit(new ClockTick(time));
                Thread.yield();
            }
        }));
        all.forEach(Thread::start);
        go.countDown();
        for (Thread thread : all) {
            thread.join();
        }
    }

    private static Command command(Random random, Acks acks, int thread, int i) {
        Instrument instrument = INSTRUMENTS.get(random.nextInt(INSTRUMENTS.size()));
        String symbol = instrument.symbol();
        // Two accounts per thread: their own orders can self-trade, and other threads' orders give real fills.
        long account = 1 + thread * 2L + random.nextInt(2);
        String clientId = "t" + thread + "-" + i;
        // Mostly an order this account owns (it may have filled or been cancelled since); sometimes a made-up id.
        Known known = random.nextInt(10) == 0 ? null : acks.recent(account, random);
        String targetSymbol = known == null ? symbol : known.symbol();
        long target = known == null ? 1 + random.nextInt(1 + i) : known.orderId();
        int roll = random.nextInt(1_000);
        if (roll < 600) {
            return new NewOrder(
                    clientId,
                    account,
                    symbol,
                    random.nextBoolean() ? Side.BUY : Side.SELL,
                    OrderType.LIMIT,
                    price(random, instrument),
                    1 + random.nextInt(100));
        } else if (roll < 680) {
            return new NewOrder(
                    clientId,
                    account,
                    symbol,
                    random.nextBoolean() ? Side.BUY : Side.SELL,
                    OrderType.MARKET,
                    0,
                    1 + random.nextInt(50));
        } else if (roll < 850) {
            return new CancelOrder(clientId, account, targetSymbol, target);
        } else if (roll < 980) {
            return new ModifyOrder(
                    clientId, account, targetSymbol, target, price(random, instrument), 1 + random.nextInt(100));
        } else if (roll < 998) {
            // Mostly re-enables, so an account is rarely off for long.
            return new SetAccountEnabled(account, roll < 996);
        } else if (thread == 0 && roll == 998) {
            // Ops halts; the override stands until ops reopens or the next scheduled boundary (ADR 0006).
            return new SetSessionState(SessionState.HALTED);
        } else {
            return new SetSessionState(SessionState.OPEN);
        }
    }

    // On the tick, within one percent beyond the band either side, so a few are rejected.
    private static long price(Random random, Instrument instrument) {
        long tick = instrument.tickSize();
        long spread = instrument.referencePrice() * (instrument.bandPercent() + 1) / 100 / tick;
        return instrument.referencePrice() + (random.nextLong(2 * spread + 1) - spread) * tick;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
