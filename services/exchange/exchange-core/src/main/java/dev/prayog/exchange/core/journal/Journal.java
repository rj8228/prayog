package dev.prayog.exchange.core.journal;

import java.io.IOException;
import org.agrona.DirectBuffer;

/**
 * An append-only log of records, each tagged with a sequence number. Records become durable only after
 * {@link #flush()}: the caller decides how many appends one disk flush covers ("group commit").
 *
 * <p>Single writer: one thread appends and flushes.
 */
public interface Journal extends AutoCloseable {

    /**
     * Adds a record. It may sit in memory until the next {@link #flush()}.
     *
     * @param seq must be greater than {@link #lastSeq()}
     */
    void append(long seq, DirectBuffer payload, int offset, int length) throws IOException;

    /** Writes everything appended so far and waits until the disk has it (fsync). */
    void flush() throws IOException;

    /** The sequence number of the last appended record, or -1 if there is none. */
    long lastSeq();

    /** Flushes, then releases the files. */
    @Override
    void close() throws IOException;
}
