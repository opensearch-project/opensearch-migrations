package org.opensearch.migrations.bulkload.workcoordination;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CrashVerdictReaderTest {

    @Test
    void exitThreeIsOom() {
        var records = CrashVerdictReader.parseJsonlLines(List.of(
            "{\"finishedAt\": 1000, \"exitCode\": 3, \"reason\": \"Error\", \"restartCount\": 1}"));
        Assertions.assertEquals(1, records.size());
        Assertions.assertTrue(records.get(0).isOom());
    }

    @Test
    void oomKilledReasonIsOomEvenWithExit137() {
        var records = CrashVerdictReader.parseJsonlLines(List.of(
            "{\"finishedAt\": 1000, \"exitCode\": 137, \"reason\": \"OOMKilled\", \"restartCount\": 2}"));
        Assertions.assertTrue(records.get(0).isOom());
    }

    @Test
    void bare137IsNotOomEvidence() {
        // SIGKILL of unknown provenance: kill -9, eviction, or the wrapper's force-kill.
        var records = CrashVerdictReader.parseJsonlLines(List.of(
            "{\"finishedAt\": 1000, \"exitCode\": 137, \"reason\": \"Error\", \"restartCount\": 1}",
            "{\"finishedAt\": 1001, \"exitCode\": 137, \"restartCount\": 1}"));
        Assertions.assertFalse(records.get(0).isOom());
        Assertions.assertFalse(records.get(1).isOom());
    }

    @Test
    void ordinaryExitCodesAreNotOom() {
        for (var code : List.of(0, 1, 2, 4, 5, 6, 7)) {
            var records = CrashVerdictReader.parseJsonlLines(List.of(
                "{\"finishedAt\": 1000, \"exitCode\": " + code + ", \"reason\": \"Error\"}"));
            Assertions.assertFalse(records.get(0).isOom(), "exit " + code + " must not be OOM evidence");
        }
    }

    @Test
    void finishedAtAcceptsIsoTimestampsAndEpochSeconds() {
        var records = CrashVerdictReader.parseJsonlLines(List.of(
            "{\"finishedAt\": \"2026-11-20T01:02:03Z\", \"exitCode\": 3}",
            "{\"finishedAt\": 1763600000, \"exitCode\": 3}"));
        Assertions.assertEquals(java.time.Instant.parse("2026-11-20T01:02:03Z").getEpochSecond(),
            records.get(0).finishedAtEpochSeconds());
        Assertions.assertEquals(1763600000L, records.get(1).finishedAtEpochSeconds());
    }

    @Test
    void malformedAndBlankLinesAreSkipped() {
        var records = CrashVerdictReader.parseJsonlLines(List.of(
            "",
            "{\"finishedAt\": 1000, \"exitCode\": 3}",
            "{\"exitCode\": 3}",                      // missing finishedAt
            "{\"finishedAt\": \"not-a-time\", \"exitCode\": 3}",
            "{ torn write"));
        Assertions.assertEquals(1, records.size());
    }

    @Test
    void newestCoveringVerdictHonorsSkewAndRecency() {
        var records = CrashVerdictReader.parseJsonlLines(List.of(
            "{\"finishedAt\": 900, \"exitCode\": 3}",     // too old even with skew
            "{\"finishedAt\": 990, \"exitCode\": 1}",     // covered via 15s skew (>= 1000-15)
            "{\"finishedAt\": 1050, \"exitCode\": 3}",    // newest covering
            "{\"finishedAt\": 1020, \"exitCode\": 137}"));
        var covering = CrashVerdictReader.newestCoveringVerdict(records, 1000, 15);
        Assertions.assertTrue(covering.isPresent());
        Assertions.assertEquals(1050, covering.get().finishedAtEpochSeconds());

        var none = CrashVerdictReader.newestCoveringVerdict(records, 2000, 15);
        Assertions.assertTrue(none.isEmpty());
    }
}
