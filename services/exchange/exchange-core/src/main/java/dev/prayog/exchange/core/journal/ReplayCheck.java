package dev.prayog.exchange.core.journal;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Command-line replay check for a journal directory: exit code 0 when replay reproduces the recorded event log, 1 when
 * it does not, 2 on bad usage or an unreadable journal.
 *
 * <pre>
 * java --add-exports java.base/jdk.internal.misc=ALL-UNNAMED -cp ... \
 *     dev.prayog.exchange.core.journal.ReplayCheck /data/journal
 * </pre>
 */
public final class ReplayCheck {

    private ReplayCheck() {}

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("usage: ReplayCheck <journal directory>");
            System.exit(2);
        }
        try {
            Replay.Report report = Replay.check(Path.of(args[0]));
            System.out.printf("commands  %d%n", report.commands());
            System.out.printf(
                    "recorded  %d events  sha256 %s%n",
                    report.recorded().records(), report.recorded().sha256());
            System.out.printf(
                    "replayed  %d events  sha256 %s%n",
                    report.replayed().records(), report.replayed().sha256());
            if (report.matches()) {
                System.out.println("MATCH");
                System.exit(0);
            }
            System.out.println("MISMATCH: " + report.firstDifference());
            System.exit(1);
        } catch (IOException | RuntimeException e) {
            System.err.println("replay failed: " + e.getMessage());
            System.exit(2);
        }
    }
}
