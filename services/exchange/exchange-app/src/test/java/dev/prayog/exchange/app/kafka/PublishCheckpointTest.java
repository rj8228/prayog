package dev.prayog.exchange.app.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublishCheckpointTest {

    @TempDir
    Path dir;

    @Test
    void theLineOnlyPassesASeqOnceItIsAcked() {
        PublishCheckpoint checkpoint = PublishCheckpoint.load(dir.resolve("cp"));
        for (long seq = 1; seq <= 5; seq++) {
            checkpoint.sent(seq);
        }
        checkpoint.acked(2); // other partitions answer first
        checkpoint.acked(3);
        checkpoint.acked(5);
        assertThat(checkpoint.line()).isZero();
        checkpoint.acked(1);
        assertThat(checkpoint.line()).isEqualTo(3); // 4 still outstanding
        checkpoint.acked(4);
        assertThat(checkpoint.line()).isEqualTo(5);
        assertThat(checkpoint.inFlight()).isZero();
    }

    @Test
    void gapsInTheEventSeqDoNotHoldTheLineBack() {
        PublishCheckpoint checkpoint = PublishCheckpoint.load(dir.resolve("cp"));
        checkpoint.sent(10);
        checkpoint.sent(20);
        checkpoint.acked(10);
        assertThat(checkpoint.line()).isEqualTo(19);
        checkpoint.acked(20);
        assertThat(checkpoint.line()).isEqualTo(20);
    }

    @Test
    void savesAndLoadsTheLine() throws IOException {
        Path file = dir.resolve("cp");
        PublishCheckpoint checkpoint = PublishCheckpoint.load(file);
        checkpoint.sent(7);
        checkpoint.acked(7);
        checkpoint.save();
        assertThat(PublishCheckpoint.load(file).line()).isEqualTo(7);
        assertThat(Files.exists(dir.resolve("cp.tmp"))).isFalse();
    }

    @Test
    void aMissingOrDamagedFileMeansResendEverything() throws IOException {
        assertThat(PublishCheckpoint.load(dir.resolve("absent")).line()).isZero();
        Path bad = dir.resolve("bad");
        Files.writeString(bad, "12x");
        assertThat(PublishCheckpoint.load(bad).line()).isZero();
    }

    @Test
    void rewindResumesAfterTheLine() {
        PublishCheckpoint checkpoint = PublishCheckpoint.load(dir.resolve("cp"));
        checkpoint.sent(1);
        checkpoint.sent(2);
        checkpoint.acked(1);
        checkpoint.rewind(); // the producer failed; 2 may or may not have reached Kafka
        assertThat(checkpoint.line()).isEqualTo(1);
        assertThat(checkpoint.inFlight()).isZero();
        checkpoint.sent(2); // allowed again
        checkpoint.acked(2);
        assertThat(checkpoint.line()).isEqualTo(2);
    }

    @Test
    void seqsMustBeSentInOrder() {
        PublishCheckpoint checkpoint = PublishCheckpoint.load(dir.resolve("cp"));
        checkpoint.sent(5);
        assertThatThrownBy(() -> checkpoint.sent(5)).isInstanceOf(IllegalArgumentException.class);
    }
}
