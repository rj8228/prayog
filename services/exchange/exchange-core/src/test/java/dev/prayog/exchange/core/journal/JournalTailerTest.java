package dev.prayog.exchange.core.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JournalTailerTest {

    private static final String NAME = "events";

    @TempDir
    Path dir;

    private final List<String> seen = new ArrayList<>();
    private final RecordVisitor collect =
            (seq, buffer, offset, length) -> seen.add(seq + ":" + buffer.getStringWithoutLengthUtf8(offset, length));

    @Test
    void readsEverythingFromTheStartAcrossSegments() throws IOException {
        try (FileJournal journal = FileJournal.open(dir, NAME, 200)) {
            appendRange(journal, 1, 40);
        }
        try (JournalTailer tailer = new JournalTailer(dir, NAME, 1)) {
            while (tailer.poll(Long.MAX_VALUE, 7, collect) > 0) {}
            assertThat(seen).hasSize(40).first().isEqualTo("1:r1");
            assertThat(seen).last().isEqualTo("40:r40");
            assertThat(tailer.lastSeq()).isEqualTo(40);
        }
    }

    @Test
    void startsAtTheRequestedSeqEvenInALaterSegment() throws IOException {
        try (FileJournal journal = FileJournal.open(dir, NAME, 200)) {
            appendRange(journal, 1, 40);
        }
        try (JournalTailer tailer = new JournalTailer(dir, NAME, 33)) {
            tailer.poll(Long.MAX_VALUE, 1000, collect);
        }
        assertThat(seen)
                .containsExactly("33:r33", "34:r34", "35:r35", "36:r36", "37:r37", "38:r38", "39:r39", "40:r40");
    }

    @Test
    void neverReadsPastTheDurableSeq() throws IOException {
        try (FileJournal journal = FileJournal.open(dir, NAME, 200)) {
            appendRange(journal, 1, 10);
        }
        try (JournalTailer tailer = new JournalTailer(dir, NAME, 1)) {
            assertThat(tailer.poll(4, 1000, collect)).isEqualTo(4);
            assertThat(tailer.poll(4, 1000, collect)).isZero();
            assertThat(tailer.poll(10, 1000, collect)).isEqualTo(6);
        }
        assertThat(seen).hasSize(10).last().isEqualTo("10:r10");
    }

    @Test
    void followsAJournalThatIsStillBeingWritten() throws IOException {
        try (FileJournal journal = FileJournal.open(dir, NAME, 200);
                JournalTailer tailer = new JournalTailer(dir, NAME, 1)) {
            assertThat(tailer.poll(Long.MAX_VALUE, 1000, collect)).isZero(); // nothing written yet
            for (int seq = 1; seq <= 60; seq++) {
                append(journal, seq, "r" + seq);
                journal.flush();
                if (seq % 3 == 0) {
                    tailer.poll(journal.lastSeq(), 1000, collect);
                }
            }
            tailer.poll(journal.lastSeq(), 1000, collect);
        }
        assertThat(seen).hasSize(60);
        for (int i = 0; i < 60; i++) {
            assertThat(seen.get(i)).isEqualTo((i + 1) + ":r" + (i + 1));
        }
    }

    @Test
    void stopsAtAHalfWrittenLastRecordAndPicksItUpOnceComplete() throws IOException {
        try (FileJournal journal = FileJournal.open(dir, NAME)) {
            appendRange(journal, 1, 3);
        }
        Path segment = Segments.list(dir, NAME).getFirst();
        long complete;
        try (RandomAccessFile file = new RandomAccessFile(segment.toFile(), "rw")) {
            complete = file.length();
            file.seek(complete);
            file.write(new byte[] {9, 0}); // the start of a length field: the writer is mid-record
        }
        try (JournalTailer tailer = new JournalTailer(dir, NAME, 1)) {
            assertThat(tailer.poll(Long.MAX_VALUE, 1000, collect)).isEqualTo(3);
            assertThat(tailer.poll(Long.MAX_VALUE, 1000, collect)).isZero();
        }
    }

    @Test
    void anEmptyOrMissingJournalHasNothingYet() throws IOException {
        try (JournalTailer tailer = new JournalTailer(dir.resolve("not-there"), NAME, 1)) {
            assertThat(tailer.poll(Long.MAX_VALUE, 1000, collect)).isZero();
            assertThat(tailer.lastSeq()).isEqualTo(0);
        }
    }

    @Test
    void aCorruptRecordBelowTheDurableSeqIsAnError() throws IOException {
        try (FileJournal journal = FileJournal.open(dir, NAME)) {
            appendRange(journal, 1, 5);
        }
        Path segment = Segments.list(dir, NAME).getFirst();
        try (RandomAccessFile file = new RandomAccessFile(segment.toFile(), "rw")) {
            file.seek(Segments.HEADER_LENGTH + Segments.RECORD_PREFIX); // first payload byte of record 1
            file.write('X');
        }
        try (JournalTailer tailer = new JournalTailer(dir, NAME, 1)) {
            assertThatThrownBy(() -> tailer.poll(5, 1000, collect))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("corrupt");
        }
    }

    private static void appendRange(FileJournal journal, int from, int to) throws IOException {
        for (int seq = from; seq <= to; seq++) {
            append(journal, seq, "r" + seq);
        }
        journal.flush();
    }

    private static void append(Journal journal, long seq, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        journal.append(seq, new UnsafeBuffer(bytes), 0, bytes.length);
    }
}
