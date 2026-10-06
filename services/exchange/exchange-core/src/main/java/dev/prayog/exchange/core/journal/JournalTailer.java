package dev.prayog.exchange.core.journal;

import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.zip.CRC32C;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Follows a journal that another thread is still writing, like {@code tail -f}: each {@link #poll} hands over the
 * records written since the last one. It remembers the segment and byte position it reached, so a poll costs only the
 * new bytes, not a rescan of the whole journal (which {@link JournalReader} does).
 *
 * <p>Callers pass {@code upToSeq}, the highest seq known to be flushed to disk. Records above it may still be in the
 * writer's buffer or only half written; they are left for a later poll. A record that fails its CRC below that line
 * is real corruption and throws. With {@code upToSeq = Long.MAX_VALUE} (reading a journal nobody is writing) a bad
 * record at the very end is treated as a torn tail and the poll simply stops there, like {@link JournalReader}.
 *
 * <p>Single thread: one tailer is used by one thread.
 */
public final class JournalTailer implements AutoCloseable {

    private final Path dir;
    private final String name;
    private final long fromSeq;
    private final CRC32C crc = new CRC32C();

    private Path segment; // the segment being read, or null before the first record exists
    private FileChannel channel;
    private long position; // byte offset of the next record in segment
    private long lastSeq; // the last seq handed over, or fromSeq - 1

    /** Starts at the first record with {@code seq >= fromSeq}. */
    public JournalTailer(Path dir, String name, long fromSeq) {
        this.dir = dir;
        this.name = name;
        this.fromSeq = fromSeq;
        this.lastSeq = fromSeq - 1;
    }

    /** The last seq handed to a visitor ({@code fromSeq - 1} before the first). */
    public long lastSeq() {
        return lastSeq;
    }

    /**
     * Visits up to {@code max} new records with {@code seq <= upToSeq}, in order. Returns how many it visited; 0 means
     * nothing new yet.
     */
    public int poll(long upToSeq, int max, RecordVisitor visitor) throws IOException {
        int visited = 0;
        while (visited < max && lastSeq < upToSeq) {
            // List before reading: if a later segment already exists, the writer had finished this one when we looked,
            // so reaching its end means moving on rather than waiting.
            List<Path> files = Segments.list(dir, name);
            if (segment == null && !open(files)) {
                return visited;
            }
            Path next = nextSegment(files);
            long before = position;
            visited += readSegment(upToSeq, max - visited, visitor, next != null);
            if (position > before) {
                continue; // made progress (records handed over, or skipped below fromSeq)
            }
            if (next == null || position < channel.size()) {
                break; // the writer is still on this segment, or the next record is above upToSeq
            }
            switchTo(next, Segments.HEADER_LENGTH);
        }
        return visited;
    }

    // Opens the segment that should hold fromSeq: the last one whose first seq is <= fromSeq.
    private boolean open(List<Path> files) throws IOException {
        if (files.isEmpty()) {
            return false;
        }
        Path start = files.getFirst();
        for (Path file : files) {
            if (firstSeq(file) <= fromSeq) {
                start = file;
            }
        }
        switchTo(start, Segments.HEADER_LENGTH);
        return true;
    }

    private int readSegment(long upToSeq, int max, RecordVisitor visitor, boolean finished) throws IOException {
        long size = channel.size();
        if (position == Segments.HEADER_LENGTH && size >= Segments.HEADER_LENGTH) {
            checkHeader(size);
        }
        if (size <= position) {
            return 0;
        }
        if (size - position > Integer.MAX_VALUE) {
            throw new IOException("segment larger than 2 GiB: " + segment);
        }
        MappedByteBuffer mapped = channel.map(FileChannel.MapMode.READ_ONLY, position, size - position);
        mapped.order(ByteOrder.LITTLE_ENDIAN);
        UnsafeBuffer view = new UnsafeBuffer(mapped);
        int at = 0;
        int visited = 0;
        int available = mapped.capacity();
        while (visited < max && at + Segments.RECORD_OVERHEAD <= available) {
            int length = mapped.getInt(at);
            boolean fits = length >= 0
                    && length <= Segments.MAX_PAYLOAD
                    && at + Segments.RECORD_OVERHEAD + (long) length <= available;
            if (!fits || !crcMatches(mapped, at, length)) {
                // Half written, or damaged. Below the durable line, or in a segment the writer has left, that is
                // corruption; above it the writer simply has not finished.
                if (finished || lastSeq < upToSeq && upToSeq != Long.MAX_VALUE) {
                    throw new IOException("corrupt journal record in " + segment + " at byte " + (position + at));
                }
                break;
            }
            long seq = mapped.getLong(at + 4);
            if (seq > upToSeq) {
                break;
            }
            if (seq >= fromSeq) {
                if (seq <= lastSeq) {
                    throw new IOException("sequence went backwards in " + segment + ": " + seq + " after " + lastSeq);
                }
                visitor.onRecord(seq, view, at + Segments.RECORD_PREFIX, length);
                lastSeq = seq;
                visited++;
            }
            at += Segments.RECORD_OVERHEAD + length;
        }
        position += at;
        return visited;
    }

    private boolean crcMatches(MappedByteBuffer mapped, int at, int length) {
        crc.reset();
        crc.update(mapped.slice(at, Segments.RECORD_PREFIX + length));
        return (int) crc.getValue() == mapped.getInt(at + Segments.RECORD_PREFIX + length);
    }

    private void checkHeader(long size) throws IOException {
        MappedByteBuffer header = channel.map(FileChannel.MapMode.READ_ONLY, 0, Segments.HEADER_LENGTH);
        header.order(ByteOrder.LITTLE_ENDIAN);
        if (header.getInt(0) != Segments.MAGIC || header.getShort(4) != Segments.FORMAT_VERSION) {
            throw new IOException("not a journal segment (bad header): " + segment + " (" + size + " bytes)");
        }
    }

    private Path nextSegment(List<Path> files) {
        for (Path file : files) {
            if (file.getFileName().toString().compareTo(segment.getFileName().toString()) > 0) {
                return file;
            }
        }
        return null;
    }

    private void switchTo(Path file, long at) throws IOException {
        if (channel != null) {
            channel.close();
        }
        segment = file;
        channel = FileChannel.open(file, StandardOpenOption.READ);
        position = at;
    }

    private long firstSeq(Path file) {
        String fileName = file.getFileName().toString();
        return Long.parseLong(fileName.substring(name.length() + 1, fileName.length() - ".seg".length()));
    }

    @Override
    public void close() throws IOException {
        if (channel != null) {
            channel.close();
            channel = null;
        }
    }
}
