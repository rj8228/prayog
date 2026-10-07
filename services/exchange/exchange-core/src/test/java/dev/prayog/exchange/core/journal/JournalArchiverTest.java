package dev.prayog.exchange.core.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JournalArchiverTest {

    private static final String NAME = "input";

    @TempDir
    Path dir;

    @Test
    void archivesOnlyClosedSegmentsWhoseRecordsAreAllAtOrBelowTheSafeSeq() throws IOException {
        write(1, 60);
        List<Path> live = Segments.listLive(dir, NAME);
        long safe = Segments.firstSeq(live.get(3)) - 1; // segments 0..2 end at or below it

        JournalArchiver.Result result = JournalArchiver.archive(dir, NAME, safe);

        assertThat(result.segments()).isEqualTo(3);
        assertThat(Segments.listLive(dir, NAME)).isEqualTo(live.subList(3, live.size()));
        assertThat(Segments.list(dir, NAME)).hasSize(live.size());
        assertThat(Segments.list(dir, NAME).subList(0, 3)).allMatch(Segments::isArchived);
    }

    @Test
    void neverArchivesTheSegmentBeingWritten() throws IOException {
        write(1, 60);
        List<Path> live = Segments.listLive(dir, NAME);

        JournalArchiver.archive(dir, NAME, Long.MAX_VALUE);

        assertThat(Segments.listLive(dir, NAME)).containsExactly(live.getLast());
    }

    @Test
    void readersSeeTheSameHistoryAfterArchiving() throws IOException {
        write(1, 60);
        List<String> before = readAll();

        JournalArchiver.archive(dir, NAME, Long.MAX_VALUE);

        assertThat(readAll()).isEqualTo(before).hasSize(60);
    }

    @Test
    void aTailerCrossesFromArchivedIntoLiveSegments() throws IOException {
        write(1, 60);
        JournalArchiver.archive(dir, NAME, 40);
        List<Long> seen = new ArrayList<>();

        try (JournalTailer tailer = new JournalTailer(dir, NAME, 1)) {
            while (tailer.poll(Long.MAX_VALUE, 7, (seq, buffer, offset, length) -> seen.add(seq)) > 0) {}
        }

        assertThat(seen).hasSize(60).startsWith(1L).endsWith(60L).isSorted();
    }

    @Test
    void aTailerCanStartInsideAnArchivedSegment() throws IOException {
        write(1, 60);
        JournalArchiver.archive(dir, NAME, Long.MAX_VALUE);
        List<Long> seen = new ArrayList<>();

        try (JournalTailer tailer = new JournalTailer(dir, NAME, 5)) {
            tailer.poll(Long.MAX_VALUE, 3, (seq, buffer, offset, length) -> seen.add(seq));
        }

        assertThat(seen).containsExactly(5L, 6L, 7L);
    }

    @Test
    void theJournalReopensAndKeepsAppendingAfterArchiving() throws IOException {
        write(1, 60);
        JournalArchiver.archive(dir, NAME, Long.MAX_VALUE);

        try (FileJournal journal = FileJournal.open(dir, NAME, 200)) {
            assertThat(journal.lastSeq()).isEqualTo(60);
            append(journal, 61, "r61");
        }

        assertThat(readAll()).hasSize(61).last().isEqualTo("61:r61");
    }

    @Test
    void archivedSegmentsAreSmaller() throws IOException {
        write(1, 60);
        long liveBytes = JournalArchiver.usage(dir, NAME).liveBytes();

        JournalArchiver.Result result = JournalArchiver.archive(dir, NAME, Long.MAX_VALUE);

        assertThat(result.archivedBytes()).isLessThan(result.originalBytes());
        JournalArchiver.Usage usage = JournalArchiver.usage(dir, NAME);
        assertThat(usage.liveBytes() + result.originalBytes()).isEqualTo(liveBytes);
        assertThat(usage.archivedBytes()).isEqualTo(result.archivedBytes());
    }

    @Test
    void aTempFileLeftByACrashIsIgnoredByReadersAndCleanedUpNextTime() throws IOException {
        write(1, 60);
        Path first = Segments.listLive(dir, NAME).getFirst();
        Path temp = Segments.archivedPath(dir, NAME, Segments.firstSeq(first)).resolveSibling("half-written.tmp");
        Files.createDirectories(temp.getParent());
        Files.writeString(temp, "half a gzip stream");

        assertThat(readAll()).hasSize(60);
        JournalArchiver.archive(dir, NAME, Long.MAX_VALUE);

        assertThat(temp).doesNotExist();
        assertThat(readAll()).hasSize(60);
    }

    @Test
    void aCrashAfterTheRenameReadsEachRecordOnceAndTheNextRunRemovesTheOriginal() throws IOException {
        write(1, 60);
        Path first = Segments.listLive(dir, NAME).getFirst();
        Path archived = Segments.archivedPath(dir, NAME, Segments.firstSeq(first));
        Files.createDirectories(archived.getParent());
        JournalArchiver.compress(first, archived); // the original was not deleted yet

        assertThat(readAll()).hasSize(60);
        JournalArchiver.archive(dir, NAME, Long.MAX_VALUE);

        assertThat(first).doesNotExist();
        assertThat(readAll()).hasSize(60);
    }

    @Test
    void anArchiveThatDiffersFromItsOriginalIsNotTrusted() throws IOException {
        write(1, 60);
        Path first = Segments.listLive(dir, NAME).getFirst();
        Path archived = Segments.archivedPath(dir, NAME, Segments.firstSeq(first));
        Files.createDirectories(archived.getParent());
        JournalArchiver.compress(Segments.listLive(dir, NAME).get(1), archived); // wrong content

        assertThatThrownBy(() -> JournalArchiver.archive(dir, NAME, Long.MAX_VALUE))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("differs");
        assertThat(first).exists();
    }

    @Test
    void aSegmentArchivedBetweenListingAndReadingIsFoundInTheArchive() throws IOException {
        write(1, 60);
        Path first = Segments.listLive(dir, NAME).getFirst();
        List<String> seen = new ArrayList<>();
        JournalArchiver.archive(dir, NAME, Long.MAX_VALUE);

        Segments.scan(first, -1, (seq, buffer, offset, length) -> seen.add(String.valueOf(seq)));

        assertThat(seen).isNotEmpty().first().isEqualTo("1");
    }

    @Test
    void aDamagedArchiveIsAnError() throws IOException {
        write(1, 60);
        JournalArchiver.archive(dir, NAME, Long.MAX_VALUE);
        Path archived = Segments.list(dir, NAME).getFirst();
        byte[] bytes = Files.readAllBytes(archived);
        bytes[bytes.length / 2] ^= 0x5A;
        Files.write(archived, bytes);

        assertThatThrownBy(this::readAll).isInstanceOf(IOException.class);
    }

    private void write(int from, int to) throws IOException {
        try (FileJournal journal = FileJournal.open(dir, NAME, 200)) {
            for (int seq = from; seq <= to; seq++) {
                append(journal, seq, "r" + seq);
            }
        }
    }

    private List<String> readAll() throws IOException {
        List<String> out = new ArrayList<>();
        JournalReader.read(dir, NAME, (seq, buffer, offset, length) -> {
            byte[] bytes = new byte[length];
            buffer.getBytes(offset, bytes);
            out.add(seq + ":" + new String(bytes, StandardCharsets.UTF_8));
        });
        return out;
    }

    private static void append(Journal journal, long seq, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        journal.append(seq, new UnsafeBuffer(bytes), 0, bytes.length);
    }
}
