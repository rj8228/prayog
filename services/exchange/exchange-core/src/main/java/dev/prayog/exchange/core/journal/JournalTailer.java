package dev.prayog.exchange.core.journal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.NoSuchFileException;
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
 * <p>Archived segments ({@link JournalArchiver}) are read the same way, from their gunzipped bytes; they are finished,
 * so the tailer simply moves on at their end.
 *
 * <p>Single thread: one tailer is used by one thread.
 */
public final class JournalTailer implements AutoCloseable {

    private final Path dir;
    private final String name;
    private final long fromSeq;
    private final CRC32C crc = new CRC32C();

    private Path segment; // the segment being read, or null before the first record exists
    private FileChannel channel; // a live segment, or null when reading an archived one
    private ByteBuffer archived; // an archived segment's bytes, or null when reading a live one
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
            if (next == null || position < size()) {
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
            if (Segments.firstSeq(file) <= fromSeq) {
                start = file;
            }
        }
        switchTo(start, Segments.HEADER_LENGTH);
        return true;
    }

    private int readSegment(long upToSeq, int max, RecordVisitor visitor, boolean finished) throws IOException {
        long size = size();
        if (position == Segments.HEADER_LENGTH && size >= Segments.HEADER_LENGTH) {
            checkHeader(size);
        }
        if (size <= position) {
            return 0;
        }
        if (size - position > Integer.MAX_VALUE) {
            throw new IOException("segment larger than 2 GiB: " + segment);
        }
        ByteBuffer mapped = region(position, (int) (size - position));
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

    private boolean crcMatches(ByteBuffer mapped, int at, int length) {
        crc.reset();
        crc.update(mapped.slice(at, Segments.RECORD_PREFIX + length));
        return (int) crc.getValue() == mapped.getInt(at + Segments.RECORD_PREFIX + length);
    }

    private void checkHeader(long size) throws IOException {
        ByteBuffer header = region(0, Segments.HEADER_LENGTH);
        if (header.getInt(0) != Segments.MAGIC || header.getShort(4) != Segments.FORMAT_VERSION) {
            throw new IOException("not a journal segment (bad header): " + segment + " (" + size + " bytes)");
        }
    }

    private Path nextSegment(List<Path> files) {
        long current = Segments.firstSeq(segment);
        for (Path file : files) {
            if (Segments.firstSeq(file) > current) {
                return file;
            }
        }
        return null;
    }

    private void switchTo(Path file, long at) throws IOException {
        close();
        segment = file;
        position = at;
        if (Segments.isArchived(file)) {
            archived = Segments.load(file);
            return;
        }
        try {
            channel = FileChannel.open(file, StandardOpenOption.READ);
        } catch (NoSuchFileException archivedSinceListed) {
            archived = Segments.load(file); // finds it in the archive
        }
    }

    private long size() throws IOException {
        return archived != null ? archived.capacity() : channel.size();
    }

    private ByteBuffer region(long from, int length) throws IOException {
        ByteBuffer bytes = archived != null
                ? archived.slice((int) from, length)
                : channel.map(FileChannel.MapMode.READ_ONLY, from, length);
        return bytes.order(ByteOrder.LITTLE_ENDIAN);
    }

    @Override
    public void close() throws IOException {
        if (channel != null) {
            channel.close();
            channel = null;
        }
        archived = null;
    }
}
