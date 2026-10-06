package dev.prayog.exchange.app.kafka;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.TreeSet;

/**
 * Where the Kafka publisher has got to: the highest event seq such that it and every event before it were acknowledged
 * by Kafka (the "contiguous acked" line). Stored in a small text file next to the journal.
 *
 * <p>Why a line and not "the last ack": Kafka acknowledges partitions independently, so seq 105 (partition 2) can be
 * acked before seq 104 (partition 0). If we saved 105 and then crashed, 104 might never be sent. The line only moves
 * past 104 once 104 itself is acked.
 *
 * <p>After a restart the publisher resends from {@code line + 1}. Anything above the line that Kafka already had is
 * sent again: delivery is <b>at least once</b>, and consumers skip duplicates by event id (the seq).
 *
 * <p>The file is replaced atomically (write a temp file, then rename), so a crash leaves either the old or the new
 * value, never half a number. It is not fsynced on every save: losing the last few saves only means resending a few
 * events, which at-least-once delivery already allows.
 */
public final class PublishCheckpoint {

    private final Path file;
    private final TreeSet<Long> pending = new TreeSet<>(); // sent, not yet acked
    private long line; // every seq <= line is acked
    private long highestSent;

    private PublishCheckpoint(Path file, long line) {
        this.file = file;
        this.line = line;
        this.highestSent = line;
    }

    /** Loads the checkpoint; a missing or unreadable file means "nothing published yet" (line 0: resend all). */
    public static PublishCheckpoint load(Path file) {
        long line = 0;
        try {
            if (Files.exists(file)) {
                line = Long.parseLong(
                        Files.readString(file, StandardCharsets.UTF_8).trim());
            }
        } catch (IOException | NumberFormatException e) {
            line = 0; // resending everything is always safe; skipping something is not
        }
        return new PublishCheckpoint(file, Math.max(0, line));
    }

    /** Every seq up to and including this one is acknowledged by Kafka. */
    public synchronized long line() {
        return line;
    }

    /** Events sent and not yet acknowledged. */
    public synchronized int inFlight() {
        return pending.size();
    }

    /** Records that {@code seq} was handed to the producer. Seqs must be sent in increasing order. */
    public synchronized void sent(long seq) {
        if (seq <= highestSent) {
            throw new IllegalArgumentException("seq " + seq + " sent after " + highestSent);
        }
        highestSent = seq;
        pending.add(seq);
    }

    /** Records Kafka's acknowledgement of {@code seq} and moves the line as far as it can go. */
    public synchronized void acked(long seq) {
        pending.remove(seq);
        line = pending.isEmpty() ? highestSent : pending.first() - 1;
    }

    /** Forgets everything in flight (after a producer failure): the next send resumes at {@code line + 1}. */
    public synchronized void rewind() {
        pending.clear();
        highestSent = line;
    }

    /** Writes the line to disk. */
    public void save() throws IOException {
        long value = line();
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, Long.toString(value), StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
