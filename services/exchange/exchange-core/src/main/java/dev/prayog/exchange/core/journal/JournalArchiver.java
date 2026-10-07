package dev.prayog.exchange.core.journal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Moves finished journal segments out of the way: each is gzipped into {@code archive/} and the original deleted, so
 * the journal directory holds only recent history while every reader still sees all of it (see {@link Segments}).
 * Nothing is ever thrown away; a full replay from seq 1 stays possible (ADR 0022).
 *
 * <p><b>Which segments.</b> Only a segment that has a later one after it (so the writer has left it) and whose records
 * are all at or below {@code upToSeq}. The caller picks {@code upToSeq} so that hot readers (recovery after the newest
 * snapshots, the Kafka publisher) never need an archived segment; archived ones are slower to read, not less correct.
 *
 * <p><b>Crash safety.</b> Per segment: write {@code .tmp}, fsync, check it gunzips to exactly the original bytes,
 * rename, fsync the directory, delete the original. A crash before the rename leaves a temp file that readers ignore
 * and the next run deletes. A crash after it leaves both copies; readers list the segment once (the live file) and
 * the next run checks the archive against it again before deleting it.
 *
 * <p>Runs on any thread except the matching thread; it only reads segments the writer has finished with.
 */
public final class JournalArchiver {

    private static final String TEMP_SUFFIX = ".tmp";

    private JournalArchiver() {}

    /** What one run did: segments archived, and their size before and after compression. */
    public record Result(int segments, long originalBytes, long archivedBytes) {}

    /** Bytes on disk: the live segments and the archived ones. */
    public record Usage(long liveBytes, long archivedBytes) {}

    /** Archives every finished segment of journal {@code name} whose records are all at or below {@code upToSeq}. */
    public static Result archive(Path dir, String name, long upToSeq) throws IOException {
        Path archiveDir = dir.resolve(Segments.ARCHIVE_DIR);
        deleteTempFiles(archiveDir);
        List<Path> live = Segments.listLive(dir, name);
        int segments = 0;
        long originalBytes = 0;
        long archivedBytes = 0;
        for (int i = 0; i < live.size() - 1; i++) {
            // The next segment starts right after this one's last record, so this bound needs no scan.
            long lastSeqAtMost = Segments.firstSeq(live.get(i + 1)) - 1;
            if (lastSeqAtMost > upToSeq) {
                break;
            }
            Path original = live.get(i);
            Path archived = Segments.archivedPath(dir, name, Segments.firstSeq(original));
            long size = Files.size(original);
            archiveOne(original, archived);
            segments++;
            originalBytes += size;
            archivedBytes += Files.size(archived);
        }
        return new Result(segments, originalBytes, archivedBytes);
    }

    /** Bytes used by journal {@code name}, live and archived. */
    public static Usage usage(Path dir, String name) throws IOException {
        long live = 0;
        for (Path file : Segments.listLive(dir, name)) {
            live += Files.size(file);
        }
        long archived = 0;
        for (Path file : Segments.list(dir, name)) {
            if (Segments.isArchived(file)) {
                archived += Files.size(file);
            }
        }
        return new Usage(live, archived);
    }

    private static void archiveOne(Path original, Path archived) throws IOException {
        Files.createDirectories(archived.getParent());
        if (!Files.exists(archived)) {
            Path temp = archived.resolveSibling(archived.getFileName() + TEMP_SUFFIX);
            compress(original, temp);
            verify(original, temp);
            try {
                Files.move(temp, archived, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, archived, StandardCopyOption.REPLACE_EXISTING);
            }
            syncDirectory(archived.getParent());
        } else {
            verify(original, archived); // left by a crash after the rename: check it before trusting it
        }
        Files.delete(original);
        syncDirectory(original.getParent());
    }

    /** Gzips {@code source} into {@code target} and fsyncs it. */
    static void compress(Path source, Path target) throws IOException {
        try (InputStream in = Files.newInputStream(source);
                OutputStream out = new GZIPOutputStream(Files.newOutputStream(target), 64 * 1024)) {
            in.transferTo(out);
        }
        try (FileChannel channel = FileChannel.open(target, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    // Byte for byte: stronger than re-checking record CRCs, and segments are small enough to hold twice.
    private static void verify(Path original, Path archived) throws IOException {
        byte[] expected = Files.readAllBytes(original);
        byte[] actual;
        try (InputStream in = new GZIPInputStream(Files.newInputStream(archived))) {
            actual = in.readAllBytes();
        }
        if (!Arrays.equals(expected, actual)) {
            throw new IOException("archived copy differs from the original segment: " + archived);
        }
    }

    private static void deleteTempFiles(Path archiveDir) throws IOException {
        if (!Files.isDirectory(archiveDir)) {
            return;
        }
        try (Stream<Path> files = Files.list(archiveDir)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(TEMP_SUFFIX))
                    .toList()) {
                Files.deleteIfExists(file);
            }
        }
    }

    // Same best effort as FileJournal: macOS refuses to fsync a directory through a channel.
    private static void syncDirectory(Path dir) {
        try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException unsupported) {
            // best effort
        }
    }
}
