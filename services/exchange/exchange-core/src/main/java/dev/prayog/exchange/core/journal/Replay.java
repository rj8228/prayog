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
