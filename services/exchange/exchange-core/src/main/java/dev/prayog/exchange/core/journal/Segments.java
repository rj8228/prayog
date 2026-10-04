package dev.prayog.exchange.core.journal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.CRC32C;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * On-disk layout shared by the writer ({@link FileJournal}) and the reader ({@link JournalReader}).
 *
 * <pre>
 * segment file  {name}-{firstSeq, 20 digits}.seg
 *   header      magic "PRYJ" (4) | format version (2) | reserved (2) | firstSeq (8)       = 16 bytes
 *   record      length (4) | seq (8) | payload (length bytes) | crc32c (4)
 *               the CRC covers length, seq and payload
 * </pre>
 *
 * All numbers little-endian, like the SBE payloads. The payload starts with SBE's own header, which names the message
 * type, so the record needs no type field of its own.
 *
 * <p>A crash can leave the last record half-written ("torn"). Its length runs past the end of the file or its CRC
 * does not match. Such a record was never flushed, so no client was told it happened; it is safe to cut off. The same
 * damage anywhere else means the disk lost data that was flushed: that is corruption, and we stop.
 */
final class Segments {

    static final int MAGIC = 0x4A595250; // bytes 'P' 'R' 'Y' 'J' when written little-endian
    static final short FORMAT_VERSION = 1;
    static final int HEADER_LENGTH = 16;
    static final int RECORD_PREFIX = 4 + 8; // length + seq
    static final int RECORD_OVERHEAD = RECORD_PREFIX + 4; // + crc32c
    static final int MAX_PAYLOAD = 16 * 1024 * 1024;
    private static final String SUFFIX = ".seg";

    private Segments() {}

    static Path path(Path dir, String name, long firstSeq) {
        return dir.resolve(String.format("%s-%020d%s", name, firstSeq, SUFFIX));
    }

    /** The journal's segment files, oldest first (zero-padded names sort in sequence order). */
    static List<Path> list(Path dir, String name) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> {
                        String file = p.getFileName().toString();
                        return file.startsWith(name + "-") && file.endsWith(SUFFIX);
                    })
                    .sorted()
                    .toList();
        }
    }

    static ByteBuffer header(long firstSeq) {
        return ByteBuffer.allocate(HEADER_LENGTH)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(MAGIC)
                .putShort(FORMAT_VERSION)
                .putShort((short) 0)
                .putLong(firstSeq)
                .flip();
    }

    /** What a scan of one segment found. {@code validEnd} is where the first bad record (if any) starts. */
    record ScanResult(long validEnd, long fileSize, long lastSeq, int records) {
        boolean torn() {
            return validEnd < fileSize;
        }
    }

    /**
     * Visits every valid record of one segment, stopping at the first one that is incomplete or fails its CRC.
     *
     * @param lastSeq the last seq seen before this segment (-1 if none); records must continue above it
     */
    static ScanResult scan(Path file, long lastSeq, RecordVisitor visitor) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = channel.size();
            if (size < HEADER_LENGTH) {
                return new ScanResult(0, size, lastSeq, 0);
            }
            if (size > Integer.MAX_VALUE) {
                throw new IOException("segment larger than 2 GiB: " + file);
            }
            MappedByteBuffer mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, size);
            mapped.order(ByteOrder.LITTLE_ENDIAN);
            checkHeader(file, mapped);
            UnsafeBuffer view = new UnsafeBuffer(mapped);
            CRC32C crc = new CRC32C();
            int position = HEADER_LENGTH;
            int records = 0;
            while (position + RECORD_OVERHEAD <= size) {
                int length = mapped.getInt(position);
                if (length < 0 || length > MAX_PAYLOAD || position + RECORD_OVERHEAD + (long) length > size) {
                    break; // the length itself is torn, or the record runs past the end
                }
                crc.reset();
                crc.update(mapped.slice(position, RECORD_PREFIX + length));
                int stored = mapped.getInt(position + RECORD_PREFIX + length);
                if ((int) crc.getValue() != stored) {
                    break;
                }
                long seq = mapped.getLong(position + 4);
                if (seq <= lastSeq) {
                    throw new IOException("sequence went backwards in " + file + " at byte " + position + ": " + seq
                            + " after " + lastSeq);
                }
                visitor.onRecord(seq, view, position + RECORD_PREFIX, length);
                lastSeq = seq;
                records++;
                position += RECORD_OVERHEAD + length;
            }
            return new ScanResult(position, size, lastSeq, records);
        }
    }

    private static void checkHeader(Path file, ByteBuffer mapped) throws IOException {
        if (mapped.getInt(0) != MAGIC) {
            throw new IOException("not a journal segment (bad magic): " + file);
        }
        short version = mapped.getShort(4);
        if (version != FORMAT_VERSION) {
            throw new IOException("unsupported journal format version " + version + ": " + file);
        }
    }
}
