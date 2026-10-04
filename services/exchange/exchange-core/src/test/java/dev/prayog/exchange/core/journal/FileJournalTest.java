package dev.prayog.exchange.core.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileJournalTest {

    private static final String NAME = "input";

    @TempDir
    Path dir;

    @Test
    void readsBackWhatWasAppendedAcrossSegments() throws IOException {
        try (FileJournal journal = FileJournal.open(dir, NAME, 200)) {
            for (int seq = 1; seq <= 50; seq++) {
                append(journal, seq, "record-" + seq);
            }
        }

        assertThat(Segments.list(dir, NAME)).hasSizeGreaterThan(5);
        assertThat(readAll()).hasSize(50).first().isEqualTo("1:record-1");
        assertThat(readAll()).last().isEqualTo("50:record-50");
    }

    @Test
    void everySegmentStaysWithinItsSize() throws IOException {
        try (FileJournal journal = FileJournal.open(dir, NAME, 200)) {
            for (int seq = 1; seq <= 50; seq++) {
                append(journal, seq, "record-" + seq);
            }
        }

        for (Path segment : Segments.list(dir, NAME)) {
            assertThat(Files.size(segment)).isLessThanOrEqualTo(200);
        }
    }

    @Test
    void aRecordLargerThanASegmentGetsASegmentOfItsOwn() throws IOException {
        String big = "x".repeat(2 * 1024 * 1024); // also larger than the 1 MiB write buffer
        try (FileJournal journal = FileJournal.open(dir, NAME, 1024)) {
            append(journal, 1, "small");
            append(journal, 2, big);
            append(journal, 3, "small again");
        }

        assertThat(readAll()).containsExactly("1:small", "2:" + big, "3:small again");
    }

    @Test
    void readsFromASequenceNumber() throws IOException {
        try (FileJournal journal = FileJournal.open(dir, NAME, 200)) {
            for (int seq = 1; seq <= 20; seq++) {
                append(journal, seq, "r" + seq);
            }
        }
        List<String> seen = new ArrayList<>();
        JournalReader.read(dir, NAME, 15, (seq, buffer, offset, length) -> seen.add(String.valueOf(seq)));

        assertThat(seen).containsExactly("15", "16", "17", "18", "19", "20");
    }

    @Test
    void reopeningContinuesAfterTheLastRecord() throws IOException {
        try (FileJournal journal = FileJournal.open(dir, NAME)) {
            append(journal, 0, "setup");
            append(journal, 1, "a");
        }
        try (FileJournal journal = FileJournal.open(dir, NAME)) {
            assertThat(journal.lastSeq()).isEqualTo(1);
            assertThat(journal.truncatedBytes()).isZero();
            append(journal, 2, "b");
        }

        assertThat(readAll()).containsExactly("0:setup", "1:a", "2:b");
    }

    @Test
    void refusesASequenceNumberThatDoesNotIncrease() throws IOException {
        try (FileJournal journal = FileJournal.open(dir, NAME)) {
            append(journal, 5, "a");

            assertThatThrownBy(() -> append(journal, 5, "b")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> append(journal, 4, "b")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void anEmptyJournalHasNoLastSeq() throws IOException {
        try (FileJournal journal = FileJournal.open(dir, NAME)) {
            assertThat(journal.lastSeq()).isEqualTo(-1);
        }
        assertThat(readAll()).isEmpty();
    }

    /** A crash can stop a write at any byte. Every such cut must recover to the records before it. */
    @Test
    void recoversFromATornLastRecordCutAtEveryByte() throws IOException {
        String lastPayload = "the-last-record";
        int lastRecordSize = Segments.RECORD_OVERHEAD + lastPayload.length();
        for (int cut = 1; cut < lastRecordSize; cut++) {
            Path run = Files.createDirectories(dir.resolve("cut-" + cut));
            try (FileJournal journal = FileJournal.open(run, NAME)) {
                append(journal, 1, "first");
                append(journal, 2, "second");
                append(journal, 3, lastPayload);
            }
            Path segment = Segments.list(run, NAME).getFirst();
            long goodEnd = Files.size(segment) - lastRecordSize;
            truncate(segment, Files.size(segment) - cut);

            try (FileJournal journal = FileJournal.open(run, NAME)) {
                assertThat(journal.lastSeq()).as("cut %d", cut).isEqualTo(2);
                assertThat(journal.truncatedBytes()).as("cut %d", cut).isEqualTo(lastRecordSize - cut);
                // The torn bytes must be gone from the file, not just skipped: a shorter record written over them
                // would otherwise leave junk behind it.
                assertThat(Files.size(segment)).as("cut %d", cut).isEqualTo(goodEnd);
                append(journal, 3, "rewritten");
            }
            assertThat(readAll(run)).as("cut %d", cut).containsExactly("1:first", "2:second", "3:rewritten");
        }
    }

    @Test
    void cutsALastRecordWhoseBytesWereDamaged() throws IOException {
        try (FileJournal journal = FileJournal.open(dir, NAME)) {
            append(journal, 1, "first");
            append(journal, 2, "second");
        }
        Path segment = Segments.list(dir, NAME).getFirst();
        flipByte(segment, Files.size(segment) - 6); // inside the last payload

        try (FileJournal journal = FileJournal.open(dir, NAME)) {
            assertThat(journal.lastSeq()).isEqualTo(1);
        }
        assertThat(readAll()).containsExactly("1:first");
    }

    @Test
    void damageInAnEarlierSegmentIsCorruptionNotATornWrite() throws IOException {
        try (FileJournal journal = FileJournal.open(dir, NAME, 100)) {
            for (int seq = 1; seq <= 10; seq++) {
                append(journal, seq, "record-" + seq);
            }
        }
        Path first = Segments.list(dir, NAME).getFirst();
        flipByte(first, Files.size(first) - 6);

        assertThatThrownBy(() -> FileJournal.open(dir, NAME, 100)).hasMessageContaining("corrupt journal segment");
        assertThatThrownBy(() -> readAll()).hasMessageContaining("corrupt journal segment");
    }

    @Test
    void aSegmentCutBeforeItsHeaderWasCompleteIsDiscarded() throws IOException {
        try (FileJournal journal = FileJournal.open(dir, NAME)) {
            append(journal, 1, "first");
        }
        Path halfMade = Segments.path(dir, NAME, 2);
        Files.write(halfMade, new byte[7]);

        try (FileJournal journal = FileJournal.open(dir, NAME)) {
            assertThat(journal.lastSeq()).isEqualTo(1);
            append(journal, 2, "second");
        }
        assertThat(Files.exists(halfMade)).isFalse();
        assertThat(readAll()).containsExactly("1:first", "2:second");
    }

    @Test
    void rejectsAFileThatIsNotAJournal() throws IOException {
        Files.write(Segments.path(dir, NAME, 0), new byte[64]);

        assertThatThrownBy(() -> FileJournal.open(dir, NAME)).hasMessageContaining("bad magic");
    }

    @Test
    void journalsWithDifferentNamesShareADirectory() throws IOException {
        try (FileJournal input = FileJournal.open(dir, "input");
                FileJournal events = FileJournal.open(dir, "events")) {
            append(input, 1, "command");
            append(events, 1, "event");
        }

        assertThat(readAll(dir, "input")).containsExactly("1:command");
        assertThat(readAll(dir, "events")).containsExactly("1:event");
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private static void append(Journal journal, long seq, String payload) throws IOException {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        journal.append(seq, new UnsafeBuffer(bytes), 0, bytes.length);
    }

    private List<String> readAll() throws IOException {
        return readAll(dir);
    }

    private static List<String> readAll(Path dir) throws IOException {
        return readAll(dir, NAME);
    }

    private static List<String> readAll(Path dir, String name) throws IOException {
        List<String> records = new ArrayList<>();
        JournalReader.read(dir, name, (seq, buffer, offset, length) -> {
            records.add(seq + ":" + buffer.getStringWithoutLengthUtf8(offset, length));
        });
        return records;
    }

    private static void truncate(Path file, long size) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.setLength(size);
        }
    }

    private static void flipByte(Path file, long position) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.seek(position);
            int b = raf.read();
            raf.seek(position);
            raf.write(b ^ 0xFF);
        }
    }
}
