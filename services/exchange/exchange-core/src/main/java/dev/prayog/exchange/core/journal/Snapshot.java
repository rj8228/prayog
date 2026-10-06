package dev.prayog.exchange.core.journal;

import dev.prayog.contracts.SessionState;
import dev.prayog.contracts.Side;
import dev.prayog.exchange.core.EngineState;
import dev.prayog.exchange.core.marketdata.OrderTracker;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.CRC32C;

/**
 * The exchange's state right after input seq {@code inputSeq} (ADR 0016): the engine, the derived order tracker, and
 * opaque bytes from the application (market statistics). Recovery loads the newest valid snapshot and replays only
 * the input after it, instead of the whole journal.
 *
 * <p>A snapshot is an optimisation, never the truth: the journal still holds everything, a damaged snapshot is skipped,
 * and recovery verifies every event replayed after it against the event log.
 *
 * <pre>
 * file   snapshot-{inputSeq, 20 digits}.snap in the journal directory
 * bytes  magic "PRYS" | version | inputSeq | eventSeq | engine | tracker | app bytes | crc32c of everything before
 * </pre>
 */
public record Snapshot(long inputSeq, long eventSeq, EngineState engine, OrderTracker.State tracker, byte[] app) {

    private static final int MAGIC = 0x50525953; // "PRYS"
    private static final short VERSION = 1;
    private static final String PREFIX = "snapshot-";
    private static final String SUFFIX = ".snap";

    public Snapshot {
        app = app == null ? new byte[0] : app.clone();
    }

    @Override
    public byte[] app() {
        return app.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Snapshot s
                && inputSeq == s.inputSeq
                && eventSeq == s.eventSeq
                && engine.equals(s.engine)
                && tracker.equals(s.tracker)
                && Arrays.equals(app, s.app);
    }

    @Override
    public int hashCode() {
        return Long.hashCode(inputSeq) * 31 + Arrays.hashCode(app);
    }

    // ---- encoding ---------------------------------------------------------------------------------------------------

    public byte[] encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            out.writeShort(VERSION);
            out.writeLong(inputSeq);
            out.writeLong(eventSeq);
            writeEngine(out, engine);
            writeTracker(out, tracker);
            out.writeInt(app.length);
            out.write(app);
            out.flush();
            CRC32C crc = new CRC32C();
            crc.update(bytes.toByteArray());
            out.writeInt((int) crc.getValue());
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e); // in-memory streams do not fail
        }
    }

    /** Parses and checks a snapshot; throws {@link IOException} if it is damaged or of an unknown version. */
    public static Snapshot decode(byte[] bytes) throws IOException {
        if (bytes.length < 4 + 2 + 16 + 4) {
            throw new IOException("snapshot too short");
        }
        CRC32C crc = new CRC32C();
        crc.update(bytes, 0, bytes.length - 4);
        int stored = ByteBuffer.wrap(bytes, bytes.length - 4, 4).getInt();
        if ((int) crc.getValue() != stored) {
            throw new IOException("snapshot checksum mismatch");
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes, 0, bytes.length - 4));
        if (in.readInt() != MAGIC) {
            throw new IOException("not a snapshot (bad magic)");
        }
        short version = in.readShort();
        if (version != VERSION) {
            throw new IOException("unsupported snapshot version " + version);
        }
        long inputSeq = in.readLong();
        long eventSeq = in.readLong();
        EngineState engine = readEngine(in);
        OrderTracker.State tracker = readTracker(in);
        byte[] app = new byte[in.readInt()];
        in.readFully(app);
        return new Snapshot(inputSeq, eventSeq, engine, tracker, app);
    }

    private static void writeEngine(DataOutputStream out, EngineState e) throws IOException {
        out.writeInt(e.rulesVersion());
        out.writeUTF(e.session().name());
        out.writeLong(e.simTime());
        out.writeBoolean(e.ticked());
        out.writeLong(e.nextEventSeq());
        out.writeLong(e.nextOrderId());
        out.writeLong(e.nextTradeId());
        out.writeInt(e.disabledAccounts().size());
        for (long account : e.disabledAccounts()) {
            out.writeLong(account);
        }
        out.writeInt(e.clientOrderIds().size());
        for (EngineState.AccountClientOrderIds a : e.clientOrderIds()) {
            out.writeLong(a.accountId());
            out.writeInt(a.ids().size());
            for (String id : a.ids()) {
                out.writeUTF(id);
            }
        }
        out.writeInt(e.orders().size());
        for (EngineState.Order o : e.orders()) {
            out.writeUTF(o.symbol());
            out.writeLong(o.orderId());
            out.writeLong(o.accountId());
            out.writeUTF(o.side().name());
            out.writeLong(o.price());
            out.writeLong(o.quantity());
            out.writeLong(o.leavesQuantity());
        }
    }

    private static EngineState readEngine(DataInputStream in) throws IOException {
        int rules = in.readInt();
        SessionState session = SessionState.valueOf(in.readUTF());
        long simTime = in.readLong();
        boolean ticked = in.readBoolean();
        long nextEventSeq = in.readLong();
        long nextOrderId = in.readLong();
        long nextTradeId = in.readLong();
        List<Long> disabled = new ArrayList<>();
        for (int i = in.readInt(); i > 0; i--) {
            disabled.add(in.readLong());
        }
        List<EngineState.AccountClientOrderIds> ids = new ArrayList<>();
        for (int i = in.readInt(); i > 0; i--) {
            long account = in.readLong();
            List<String> list = new ArrayList<>();
            for (int j = in.readInt(); j > 0; j--) {
                list.add(in.readUTF());
            }
            ids.add(new EngineState.AccountClientOrderIds(account, list));
        }
        List<EngineState.Order> orders = new ArrayList<>();
        for (int i = in.readInt(); i > 0; i--) {
            orders.add(new EngineState.Order(
                    in.readUTF(),
                    in.readLong(),
                    in.readLong(),
                    Side.valueOf(in.readUTF()),
                    in.readLong(),
                    in.readLong(),
                    in.readLong()));
        }
        return new EngineState(
                rules, session, simTime, ticked, nextEventSeq, nextOrderId, nextTradeId, disabled, ids, orders);
    }

    private static void writeTracker(DataOutputStream out, OrderTracker.State t) throws IOException {
        out.writeInt(t.openOrders().size());
        for (OrderTracker.OpenOrder o : t.openOrders()) {
            out.writeLong(o.orderId());
            out.writeUTF(o.clientOrderId());
            out.writeLong(o.accountId());
            out.writeUTF(o.symbol());
            out.writeUTF(o.side().name());
            out.writeLong(o.price());
            out.writeLong(o.quantity());
            out.writeLong(o.leavesQuantity());
        }
        out.writeUTF(t.session().name());
        out.writeLong(t.lastEventSeq());
    }

    private static OrderTracker.State readTracker(DataInputStream in) throws IOException {
        List<OrderTracker.OpenOrder> open = new ArrayList<>();
        for (int i = in.readInt(); i > 0; i--) {
            open.add(new OrderTracker.OpenOrder(
                    in.readLong(),
                    in.readUTF(),
                    in.readLong(),
                    in.readUTF(),
                    Side.valueOf(in.readUTF()),
                    in.readLong(),
                    in.readLong(),
                    in.readLong()));
        }
        return new OrderTracker.State(open, SessionState.valueOf(in.readUTF()), in.readLong());
    }

    // ---- files ------------------------------------------------------------------------------------------------------

    static Path path(Path dir, long inputSeq) {
        return dir.resolve(String.format("%s%020d%s", PREFIX, inputSeq, SUFFIX));
    }

    /**
     * Writes this snapshot to {@code dir} durably and atomically (temp file, fsync, rename), then deletes all but the
     * newest {@code keep} snapshots. Returns the file.
     */
    public Path write(Path dir, int keep) throws IOException {
        Path file = path(dir, inputSeq);
        Path temp = dir.resolve(file.getFileName() + ".tmp");
        try (FileChannel channel = FileChannel.open(
                temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(encode());
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        List<Path> all = list(dir);
        for (int i = 0; i < all.size() - keep; i++) {
            Files.deleteIfExists(all.get(i));
        }
        return file;
    }

    /** Snapshot files in {@code dir}, oldest first. */
    public static List<Path> list(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith(PREFIX) && name.endsWith(SUFFIX);
                    })
                    .sorted()
                    .toList();
        }
    }

    /**
     * The newest snapshot in {@code dir} that is readable and not beyond {@code maxInputSeq} (the end of the input
     * journal). Damaged files are skipped: an older snapshot, or a full replay, is always possible.
     */
    public static Optional<Snapshot> latest(Path dir, long maxInputSeq) throws IOException {
        List<Path> files = new ArrayList<>(list(dir));
        java.util.Collections.reverse(files);
        for (Path file : files) {
            try {
                Snapshot snapshot = decode(Files.readAllBytes(file));
                if (snapshot.inputSeq() <= maxInputSeq) {
                    return Optional.of(snapshot);
                }
            } catch (IOException | RuntimeException damaged) {
                // skip it; see the method comment
            }
        }
        return Optional.empty();
    }
}
