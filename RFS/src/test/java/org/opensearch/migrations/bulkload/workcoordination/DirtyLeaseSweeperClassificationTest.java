package org.opensearch.migrations.bulkload.workcoordination;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.workcoordination.CrashVerdictReader.VerdictRecord;
import org.opensearch.migrations.bulkload.workcoordination.DirtyLeaseSweeper.Disposition;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The sweep classification table: every verdict shape (present/absent x OOM/non-OOM) maps to
 * escalation or release.  Only positive OOM evidence (exit 3 or reason OOMKilled) climbs the
 * memory ladder; everything else — including a bare 137 and the no-verdict case — releases at
 * the same tier so a fleet-wide non-memory crasher can never multiply the run's cost.
 */
class DirtyLeaseSweeperClassificationTest {

    private static VerdictRecord verdict(Integer exitCode, String reason) {
        return new VerdictRecord(1000, exitCode, reason, 1);
    }

    static Stream<Arguments> classificationTable() {
        return Stream.of(
            // verdict, expected disposition, label
            Arguments.of(Optional.of(verdict(3, "Error")), Disposition.ESCALATE, "heap OOM: exit 3"),
            Arguments.of(Optional.of(verdict(137, "OOMKilled")), Disposition.ESCALATE, "cgroup OOM kill"),
            Arguments.of(Optional.of(verdict(null, "OOMKilled")), Disposition.ESCALATE, "OOMKilled, no code"),
            Arguments.of(Optional.of(verdict(137, "Error")), Disposition.RELEASE, "bare 137 (kill -9 / eviction)"),
            Arguments.of(Optional.of(verdict(137, null)), Disposition.RELEASE, "bare 137, no reason"),
            Arguments.of(Optional.of(verdict(1, "Error")), Disposition.RELEASE, "uncaught exception"),
            Arguments.of(Optional.of(verdict(2, "Error")), Disposition.RELEASE, "lease timeout exit"),
            Arguments.of(Optional.of(verdict(134, "Error")), Disposition.RELEASE, "JVM native crash (SIGABRT)"),
            Arguments.of(Optional.of(verdict(0, "Completed")), Disposition.RELEASE, "clean exit"),
            Arguments.of(Optional.empty(), Disposition.RELEASE, "no covering verdict arrives")
        );
    }

    @ParameterizedTest(name = "{2} -> {1}")
    @MethodSource("classificationTable")
    void classificationTableHolds(Optional<VerdictRecord> verdict, Disposition expected, String label) {
        Assertions.assertEquals(expected, DirtyLeaseSweeper.classify(verdict), label);
    }

    @Test
    void podNamePrefixSplitsOnLastUnderscore() {
        Assertions.assertEquals("my-pod-abc12_",
            DirtyLeaseSweeper.podNamePrefixOf("my-pod-abc12_c0ffee00-1111-2222-3333-444455556666"));
        // The default node base id when HOSTNAME is unset contains its own underscore.
        Assertions.assertEquals("generated__",
            DirtyLeaseSweeper.podNamePrefixOf("generated__c0ffee00-1111-2222-3333-444455556666"));
        Assertions.assertThrows(IllegalArgumentException.class,
            () -> DirtyLeaseSweeper.podNamePrefixOf("no-separator"));
    }

    @Test
    void nullVerdictFileSkipsPollingEntirely(@TempDir Path dir) throws Exception {
        var start = Instant.now();
        var result = DirtyLeaseSweeper.awaitCoveringVerdict(null, 1000,
            Instant.now().plusSeconds(10), Clock.systemUTC());
        Assertions.assertTrue(result.isEmpty());
        Assertions.assertTrue(Instant.now().isBefore(start.plusSeconds(2)),
            "must not poll when no verdict file is configured");
    }

    @Test
    void pollReturnsImmediatelyOnceACoveringVerdictExists(@TempDir Path dir) throws Exception {
        var file = dir.resolve("verdicts.jsonl");
        Files.write(file, "{\"finishedAt\": 1500, \"exitCode\": 3}\n".getBytes(StandardCharsets.UTF_8));
        var result = DirtyLeaseSweeper.awaitCoveringVerdict(file, 1000,
            Instant.now().plusSeconds(10), Clock.systemUTC());
        Assertions.assertTrue(result.isPresent());
        Assertions.assertTrue(result.get().isOom());
    }

    @Test
    void pollGivesUpAtDeadlineWithoutCoveringVerdict(@TempDir Path dir) throws Exception {
        var file = dir.resolve("verdicts.jsonl");
        // A verdict exists, but is older than the lease (not covering) - keep polling, then stop.
        Files.write(file, "{\"finishedAt\": 100, \"exitCode\": 3}\n".getBytes(StandardCharsets.UTF_8));
        var deadline = Instant.now().plusMillis(1200);
        var result = DirtyLeaseSweeper.awaitCoveringVerdict(file, 1000, deadline, Clock.systemUTC());
        Assertions.assertTrue(result.isEmpty());
        Assertions.assertFalse(Instant.now().isBefore(deadline),
            "should have polled until the deadline");
    }

    @Test
    void pollPicksUpALateAppendedVerdict(@TempDir Path dir) throws Exception {
        var file = dir.resolve("verdicts.jsonl");
        Files.createFile(file);
        var writer = new Thread(() -> {
            try {
                Thread.sleep(700);
                Files.write(file, "{\"finishedAt\": 2000, \"exitCode\": 137, \"reason\": \"OOMKilled\"}\n"
                    .getBytes(StandardCharsets.UTF_8));
            } catch (InterruptedException | IOException e) {
                Thread.currentThread().interrupt();
            }
        });
        writer.start();
        try {
            var result = DirtyLeaseSweeper.awaitCoveringVerdict(file, 1000,
                Instant.now().plusSeconds(10), Clock.systemUTC());
            Assertions.assertTrue(result.isPresent());
            Assertions.assertEquals(Disposition.ESCALATE, DirtyLeaseSweeper.classify(result));
        } finally {
            writer.join();
        }
    }
}
