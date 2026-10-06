package dev.prayog.exchange.core.journal;

import dev.prayog.exchange.core.Command;
import dev.prayog.exchange.core.MatchingEngine;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.agrona.DirectBuffer;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Deterministic replay: feeds a recorded input journal into a fresh engine, on one thread, and fingerprints the
 * events it produces. The engine reads no clock, no randomness and no I/O, so the same inputs must give byte-for-byte
 * the same events. Comparing the fingerprint with the recorded event log's proves it (BUILD_PLAN 16.1 #14, #15).
 *
 * <p>The fingerprint is SHA-256 over each event record's length, seq and SBE bytes, in order. It does not include
 * segment headers, so how the log happened to be split into files does not matter.
 */
public final class Replay {

    private Replay() {}

    /** The fingerprint of a stream of event records. */
    public record Digest(long records, String sha256) {}

    /** Outcome of {@link #check}. {@code firstDifference} is null when the logs match. */
    public record Report(long commands, Digest recorded, Digest replayed, String firstDifference) {
        public boolean matches() {
            return recorded.equals(replayed);
        }
    }

    /** Fingerprint of the event log recorded in {@code dir}. */
    public static Digest recorded(Path dir) throws IOException {
        Fingerprint fingerprint = new Fingerprint();
        JournalReader.read(dir, JournalHandler.EVENTS, fingerprint::add);
        return fingerprint.digest();
    }

    /** Replays the input journal in {@code dir} into a fresh engine and fingerprints the events it produces. */
    public static Digest replayed(Path dir) throws IOException {
        Fingerprint fingerprint = new Fingerprint();
        replay(dir, fingerprint::add);
        return fingerprint.digest();
    }

    /** Replays, compares with the recorded event log and, on a mismatch, finds the first event that differs. */
    public static Report check(Path dir) throws IOException {
        Digest recorded = recorded(dir);
        Fingerprint fingerprint = new Fingerprint();
        long commands = replay(dir, fingerprint::add);
        Digest replayed = fingerprint.digest();
        String firstDifference = recorded.equals(replayed) ? null : firstDifference(dir);
        return new Report(commands, recorded, replayed, firstDifference);
    }

    /** Outcome of {@link #checkOnline}: both fingerprints cover events 1 to {@code comparedUpTo}. */
    public record OnlineReport(long commands, long comparedUpTo, Digest recorded, Digest replayed) {
        public boolean matches() {
            return recorded.equals(replayed);
        }
    }

    /**
     * Checks a journal that is still being written, without stopping the exchange. Replays every command readable now,
     * then compares replayed and recorded events up to the last event seq both have (the event log may be slightly
     * ahead of, or behind, what the readable commands produce). A half-written last record is simply not read yet.
     */
    public static OnlineReport checkOnline(Path dir) throws IOException {
        java.util.List<byte[]> replayed = new java.util.ArrayList<>();
        long commands = replay(
                dir, (seq, buffer, offset, length) -> replayed.add(Fingerprint.bytes(seq, buffer, offset, length)));
        long replayedUpTo = replayed.size(); // event seqs are 1, 2, 3, ... with no gaps
        Fingerprint recorded = new Fingerprint();
        long[] recordedUpTo = {0};
        JournalReader.read(dir, JournalHandler.EVENTS, (seq, buffer, offset, length) -> {
            if (seq <= replayedUpTo) {
                recorded.add(seq, buffer, offset, length);
                recordedUpTo[0] = seq;
            }
        });
        Fingerprint replayedPrefix = new Fingerprint();
        for (int i = 0; i < recordedUpTo[0]; i++) {
            replayedPrefix.addBytes(replayed.get(i));
        }
        return new OnlineReport(commands, recordedUpTo[0], recorded.digest(), replayedPrefix.digest());
    }

    /**
     * Replays the input journal, handing each event record (as it would be written to the event log) to
     * {@code events}. Returns the number of commands replayed.
     */
    public static long replay(Path dir, RecordVisitor events) throws IOException {
        JournalCodec inputCodec = new JournalCodec();
        JournalCodec eventCodec = new JournalCodec();
        ExpandableArrayBuffer eventBuffer = new ExpandableArrayBuffer(1024);
        MatchingEngine[] engine = {null};
        long[] expectedSeq = {JournalHandler.SETUP_SEQ};

        JournalReader.read(dir, JournalHandler.INPUT, (seq, buffer, offset, length) -> {
            if (seq != expectedSeq[0]) {
                throw new IllegalStateException(
                        "input journal has a gap: expected seq " + expectedSeq[0] + ", found " + seq);
            }
            expectedSeq[0]++;
            if (engine[0] == null) {
                if (!inputCodec.isEngineSetup(buffer, offset)) {
                    throw new IllegalStateException("input journal does not start with an engine setup record");
                }
                engine[0] = inputCodec.decodeEngineSetup(buffer, offset).newEngine(event -> {
                    int eventLength = eventCodec.encode(event, eventBuffer, 0);
                    events.onRecord(event.seq(), eventBuffer, 0, eventLength);
                });
                return;
            }
            Command command = inputCodec.decodeCommand(buffer, offset);
            engine[0].apply(command);
        });
        if (engine[0] == null) {
            throw new IllegalStateException("input journal is empty: " + dir);
        }
        return expectedSeq[0] - 1;
    }

    /** Result of {@link #checkSnapshots}: how many snapshots matched the replay, and the first that did not. */
    public record SnapshotReport(int verified, String firstMismatch) {
        public boolean matches() {
            return firstMismatch == null;
        }
    }

    /**
     * Checks every readable snapshot in {@code dir} (ADR 0016): replays the input journal once and compares the
     * engine's state at each snapshot's input seq with what the snapshot recorded. A snapshot that recovery could
     * start from must be exactly the state a full replay reaches.
     */
    public static SnapshotReport checkSnapshots(Path dir) throws IOException {
        java.util.TreeMap<Long, Snapshot> wanted = new java.util.TreeMap<>();
        for (Path file : Snapshot.list(dir)) {
            try {
                Snapshot s = Snapshot.decode(java.nio.file.Files.readAllBytes(file));
                wanted.put(s.inputSeq(), s);
            } catch (IOException damaged) {
                // recovery skips damaged files too
            }
        }
        if (wanted.isEmpty()) {
            return new SnapshotReport(0, null);
        }
        JournalCodec codec = new JournalCodec();
        MatchingEngine[] engine = {null};
        int[] verified = {0};
        String[] mismatch = {null};
        JournalReader.read(dir, JournalHandler.INPUT, (seq, buffer, offset, length) -> {
            if (engine[0] == null) {
                engine[0] = codec.decodeEngineSetup(buffer, offset).newEngine(event -> {});
            } else {
                engine[0].apply(codec.decodeCommand(buffer, offset));
            }
            Snapshot s = wanted.get(seq);
            if (s != null && mismatch[0] == null) {
                if (engine[0].snapshot().equals(s.engine())) {
                    verified[0]++;
                } else {
                    mismatch[0] = "snapshot at input seq " + seq + " differs from the replayed engine state";
                }
            }
        });
        return new SnapshotReport(verified[0], mismatch[0]);
    }

    // Slow path, only after a mismatch: holds both logs in memory to point at the first event that differs.
    private static String firstDifference(Path dir) throws IOException {
        List<byte[]> recorded = new ArrayList<>();
        List<byte[]> replayed = new ArrayList<>();
        JournalReader.read(
                dir,
                JournalHandler.EVENTS,
                (seq, buffer, offset, length) -> recorded.add(Fingerprint.bytes(seq, buffer, offset, length)));
        replay(dir, (seq, buffer, offset, length) -> replayed.add(Fingerprint.bytes(seq, buffer, offset, length)));
        JournalCodec codec = new JournalCodec();
        int common = Math.min(recorded.size(), replayed.size());
        for (int i = 0; i < common; i++) {
            if (!Arrays.equals(recorded.get(i), replayed.get(i))) {
                return "event #" + (i + 1) + ": recorded " + describe(codec, recorded.get(i)) + ", replayed "
                        + describe(codec, replayed.get(i));
            }
        }
        return "recorded " + recorded.size() + " events, replay produced " + replayed.size();
    }

    private static String describe(JournalCodec codec, byte[] record) {
        try {
            return codec.decodeEvent(new UnsafeBuffer(record), Fingerprint.PREFIX)
                    .toString();
        } catch (RuntimeException e) {
            return "undecodable (" + e.getMessage() + ")";
        }
    }

    /** Running SHA-256 over event records. */
    private static final class Fingerprint {

        static final int PREFIX = 4 + 8; // length + seq, little-endian, like the journal record

        private final MessageDigest sha256;
        private long records;

        Fingerprint() {
            try {
                sha256 = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("every JVM provides SHA-256", e);
            }
        }

        void add(long seq, DirectBuffer buffer, int offset, int length) {
            sha256.update(bytes(seq, buffer, offset, length));
            records++;
        }

        void addBytes(byte[] bytes) {
            sha256.update(bytes);
            records++;
        }

        Digest digest() {
            return new Digest(records, HexFormat.of().formatHex(sha256.digest()));
        }

        static byte[] bytes(long seq, DirectBuffer buffer, int offset, int length) {
            byte[] bytes = new byte[PREFIX + length];
            UnsafeBuffer view = new UnsafeBuffer(bytes);
            view.putInt(0, length, ByteOrder.LITTLE_ENDIAN);
            view.putLong(4, seq, ByteOrder.LITTLE_ENDIAN);
            buffer.getBytes(offset, bytes, PREFIX, length);
            return bytes;
        }
    }
}
