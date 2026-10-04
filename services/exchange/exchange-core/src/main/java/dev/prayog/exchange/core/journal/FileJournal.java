package dev.prayog.exchange.core.journal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.zip.CRC32C;
import org.agrona.DirectBuffer;

/**
 * A {@link Journal} stored as a series of segment files in one directory (layout in {@link Segments}).
 *
 * <p>Appends go into an in-memory buffer; {@link #flush()} writes the buffer and fsyncs. A segment is closed and a new
 * one started once it would grow past {@code segmentSize}, so old segments can later be archived or deleted whole.
 *
 * <p><b>Recovery on open:</b> the last segment is scanned and cut at its first torn record (see {@link Segments}).
 * Writing then continues after the last good record.
 */
public final class FileJournal implements Journal {

    /** 64 MiB: about 1.5 million order records per segment. */
    public static final long DEFAULT_SEGMENT_SIZE = 64L * 1024 * 1024;

    private static final int WRITE_BUFFER_SIZE = 1024 * 1024;

    private final Path dir;
    private final String name;
    private final long segmentSize;
    private final CRC32C crc = new CRC32C();
    private final long truncatedBytes;

    private ByteBuffer writeBuffer =
            ByteBuffer.allocateDirect(WRITE_BUFFER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
    private FileChannel segment;
    private long segmentBytes; // written to the file + waiting in writeBuffer
    private boolean segmentHasRecords;
    private long lastSeq;
    private boolean closed;

    private FileJournal(Path dir, String name, long segmentSize) throws IOException {
        if (segmentSize < Segments.HEADER_LENGTH + Segments.RECORD_OVERHEAD || segmentSize > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("segmentSize out of range: " + segmentSize);
        }
        this.dir = dir;
        this.name = name;
        this.segmentSize = segmentSize;
        Files.createDirectories(dir);
        this.truncatedBytes = recover();
    }

    /** Opens the journal {@code name} in {@code dir}, creating it if needed and recovering from a crash if needed. */
    public static FileJournal open(Path dir, String name, long segmentSize) throws IOException {
        return new FileJournal(dir, name, segmentSize);
    }

    public static FileJournal open(Path dir, String name) throws IOException {
        return open(dir, name, DEFAULT_SEGMENT_SIZE);
    }

    /** How many bytes of torn tail were cut off when this journal was opened (0 after a clean shutdown). */
    public long truncatedBytes() {
        return truncatedBytes;
    }

    @Override
    public void append(long seq, DirectBuffer payload, int offset, int length) throws IOException {
        if (closed) {
            throw new IllegalStateException("journal closed");
        }
        if (seq <= lastSeq) {
            throw new IllegalArgumentException("seq " + seq + " is not after " + lastSeq);
        }
        if (length < 0 || length > Segments.MAX_PAYLOAD) {
            throw new IllegalArgumentException("payload length out of range: " + length);
        }
        int recordLength = Segments.RECORD_OVERHEAD + length;
        if (segment == null) {
            startSegment(seq);
        } else if (segmentHasRecords && segmentBytes + recordLength > segmentSize) {
            finishSegment();
            startSegment(seq);
        }
        if (writeBuffer.remaining() < recordLength) {
            drain();
            if (writeBuffer.capacity() < recordLength) {
                writeBuffer = ByteBuffer.allocateDirect(recordLength).order(ByteOrder.LITTLE_ENDIAN);
            }
        }
        int start = writeBuffer.position();
        writeBuffer.putInt(length).putLong(seq);
        payload.getBytes(offset, writeBuffer, writeBuffer.position(), length);
        writeBuffer.position(writeBuffer.position() + length);
        crc.reset();
        crc.update(writeBuffer.slice(start, Segments.RECORD_PREFIX + length));
        writeBuffer.putInt((int) crc.getValue());

        segmentBytes += recordLength;
        segmentHasRecords = true;
        lastSeq = seq;
    }

    @Override
    public void flush() throws IOException {
        if (segment == null) {
            return;
        }
        drain();
        segment.force(false); // fdatasync: the data and the file size, but not timestamps
    }

    @Override
    public long lastSeq() {
        return lastSeq;
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        if (segment != null) {
            flush();
            segment.close();
        }
    }

    // Writes the buffered records to the file without waiting for the disk.
    private void drain() throws IOException {
        writeBuffer.flip();
        while (writeBuffer.hasRemaining()) {
            segment.write(writeBuffer);
        }
        writeBuffer.clear();
    }

    private void startSegment(long firstSeq) throws IOException {
        Path file = Segments.path(dir, name, firstSeq);
        segment = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        ByteBuffer header = Segments.header(firstSeq);
        while (header.hasRemaining()) {
            segment.write(header);
        }
        segment.force(true);
        syncDirectory();
        segmentBytes = Segments.HEADER_LENGTH;
        segmentHasRecords = false;
    }

    private void finishSegment() throws IOException {
        flush();
        segment.close();
        segment = null;
    }

    /**
     * Scans the existing segments, cuts a torn tail off the last one and positions writing after it. Returns the
     * number of bytes cut off.
     */
    private long recover() throws IOException {
        lastSeq = -1;
        List<Path> files = Segments.list(dir, name);
        for (int i = 0; i < files.size(); i++) {
            Path file = files.get(i);
            boolean last = i == files.size() - 1;
            Segments.ScanResult scan = Segments.scan(file, lastSeq, (seq, buffer, offset, length) -> {});
            if (!last) {
                if (scan.torn()) {
                    throw new IOException(
                            "corrupt journal segment (not the last one): " + file + " at byte " + scan.validEnd());
                }
                lastSeq = scan.lastSeq();
                continue;
            }
            if (scan.fileSize() < Segments.HEADER_LENGTH) {
                // Crashed while creating the segment: it holds nothing. Continue in the previous one.
                Files.delete(file);
                syncDirectory();
                return scan.fileSize() + recover();
            }
            lastSeq = scan.lastSeq();
            segment = FileChannel.open(file, StandardOpenOption.WRITE);
            long cut = scan.fileSize() - scan.validEnd();
            if (cut > 0) {
                segment.truncate(scan.validEnd());
                segment.force(true);
            }
            segment.position(scan.validEnd());
            segmentBytes = scan.validEnd();
            segmentHasRecords = scan.records() > 0;
            return cut;
        }
        return 0;
    }

    /**
     * Makes a newly created or deleted file name durable. On Linux a directory can be fsynced through a channel; some
     * platforms (macOS) refuse to open a directory that way, and there we rely on the file system's own ordering.
     */
    private void syncDirectory() {
        try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException unsupported) {
            // best effort, see above
        }
    }
}
