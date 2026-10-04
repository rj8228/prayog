package dev.prayog.exchange.core.journal;

import org.agrona.DirectBuffer;

/** Receives journal records one at a time. The buffer is a view over the file and is valid only during the call. */
@FunctionalInterface
public interface RecordVisitor {
    void onRecord(long seq, DirectBuffer buffer, int offset, int length);
}
