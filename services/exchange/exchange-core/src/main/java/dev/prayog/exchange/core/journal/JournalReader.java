package dev.prayog.exchange.core.journal;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Reads a journal's records in sequence order, across all its segments.
 *
 * <p>An incomplete record at the very end is treated as "not written yet" and the read stops there, so this can read a
 * journal that is still being written. A bad record anywhere else is corruption and throws.
 */
public final class JournalReader {

    private JournalReader() {}

    /** Visits every record with {@code seq >= fromSeq}. Returns the number of records visited. */
    public static long read(Path dir, String name, long fromSeq, RecordVisitor visitor) throws IOException {
        List<Path> files = Segments.list(dir, name);
        long lastSeq = -1;
        long[] visited = {0};
        RecordVisitor filtered = (seq, buffer, offset, length) -> {
            if (seq >= fromSeq) {
                visitor.onRecord(seq, buffer, offset, length);
                visited[0]++;
            }
        };
        for (int i = 0; i < files.size(); i++) {
            Path file = files.get(i);
            Segments.ScanResult scan = Segments.scan(file, lastSeq, filtered);
            if (scan.torn() && i < files.size() - 1) {
                throw new IOException(
                        "corrupt journal segment (not the last one): " + file + " at byte " + scan.validEnd());
            }
            lastSeq = scan.lastSeq();
        }
        return visited[0];
    }

    /** Visits every record. */
    public static long read(Path dir, String name, RecordVisitor visitor) throws IOException {
        return read(dir, name, Long.MIN_VALUE, visitor);
    }
}
