package org.opensearch.migrations.bulkload.workcoordination;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.workcoordination.DirtyLeaseSweeper.Disposition;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The sweep classification table: how a predecessor died (as reported by the in-container
 * supervisor) maps to escalation or release.  Only positive OOM evidence (exit 3 or OOMKilled)
 * climbs the memory ladder; everything else — including a bare 137 and an unknown holder —
 * releases at the same tier so a fleet-wide non-memory crasher can never multiply the run's cost.
 */
class DirtyLeaseSweeperClassificationTest {

    static Stream<Arguments> classificationTable() {
        return Stream.of(
            // predecessor outcome, expected disposition, label
            Arguments.of(Optional.of("3"), Disposition.ESCALATE, "heap OOM: exit 3"),
            Arguments.of(Optional.of("OOMKilled"), Disposition.ESCALATE, "kernel OOM kill seen by the supervisor"),
            Arguments.of(Optional.of("137"), Disposition.RELEASE, "bare 137 (kill -9)"),
            Arguments.of(Optional.of("1"), Disposition.RELEASE, "uncaught exception"),
            Arguments.of(Optional.of("5"), Disposition.RELEASE, "snapshot read failure"),
            Arguments.of(Optional.of("134"), Disposition.RELEASE, "JVM native crash (SIGABRT)"),
            Arguments.of(Optional.empty(), Disposition.RELEASE, "holder not reported (no evidence)")
        );
    }

    @ParameterizedTest(name = "{2} -> {1}")
    @MethodSource("classificationTable")
    void classificationTableHolds(Optional<String> outcome, Disposition expected, String label) {
        Assertions.assertEquals(expected, DirtyLeaseSweeper.classify(outcome), label);
    }

    @Test
    void parsesTheSupervisorsCrashList() {
        Assertions.assertEquals(Map.of("1795570591-1", "3", "1795570593-3", "OOMKilled", "1795570597-4", "1"),
            DirtyLeaseSweeper.parsePredecessorCrashes("1795570591-1=3,1795570593-3=OOMKilled, 1795570597-4=1"));
        Assertions.assertTrue(DirtyLeaseSweeper.parsePredecessorCrashes(null).isEmpty());
        Assertions.assertTrue(DirtyLeaseSweeper.parsePredecessorCrashes("  ").isEmpty());
        // Malformed entries are skipped rather than failing the sweep.
        Assertions.assertEquals(Map.of("ok-1", "3"),
            DirtyLeaseSweeper.parsePredecessorCrashes("=3,no-equals,trailing=,ok-1=3"));
    }

    @Test
    void podNamePrefixAndInstanceIdSplitOnLastUnderscore() {
        Assertions.assertEquals("my-pod-abc12_",
            DirtyLeaseSweeper.podNamePrefixOf("my-pod-abc12_c0ffee00-1111-2222-3333-444455556666"));
        Assertions.assertEquals("1795570591-1",
            DirtyLeaseSweeper.instanceIdOf("my-pod-abc12_1795570591-1"));
        // The default node base id when HOSTNAME is unset contains its own underscore.
        Assertions.assertEquals("generated__",
            DirtyLeaseSweeper.podNamePrefixOf("generated__c0ffee00-1111-2222-3333-444455556666"));
        Assertions.assertThrows(IllegalArgumentException.class,
            () -> DirtyLeaseSweeper.podNamePrefixOf("no-separator"));
    }
}
