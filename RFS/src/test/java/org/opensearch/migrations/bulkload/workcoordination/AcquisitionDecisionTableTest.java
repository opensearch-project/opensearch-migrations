package org.opensearch.migrations.bulkload.workcoordination;

import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.workcoordination.OpenSearchWorkCoordinator.AcquisitionPrecheckResult;
import org.opensearch.migrations.bulkload.workcoordination.OpenSearchWorkCoordinator.TierWorkCensus;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The acquire protocol's decision table:
 * <ol>
 *   <li>nothing incomplete anywhere → NO_WORK_LEFT (drained, exit 6)</li>
 *   <li>leased[myTier] >= cap[myTier] → LEASE_CAP_REACHED (idle, exit 7)</li>
 *   <li>waiting work at my tier → ACQUIRE</li>
 *   <li>else → NO_WORK_AVAILABLE_FOR_TIER (exit 4)</li>
 * </ol>
 * A worker idles only because of its OWN tier's cap — never because work exists elsewhere.
 */
class AcquisitionDecisionTableTest {

    static Stream<Arguments> decisionTable() {
        return Stream.of(
            // totalIncomplete, waitingAtTier, leasedAtTier, cap (null = none), expected
            Arguments.of(0L, 0L, 0L, null, AcquisitionPrecheckResult.NO_WORK_LEFT),
            Arguments.of(0L, 0L, 0L, 5L, AcquisitionPrecheckResult.NO_WORK_LEFT),
            // Drain beats the cap check: nothing left is terminal regardless of caps.
            Arguments.of(0L, 0L, 0L, 0L, AcquisitionPrecheckResult.NO_WORK_LEFT),

            // Own-tier cap reached: idle even though waiting work exists at this tier.
            Arguments.of(10L, 4L, 5L, 5L, AcquisitionPrecheckResult.LEASE_CAP_REACHED),
            Arguments.of(10L, 4L, 6L, 5L, AcquisitionPrecheckResult.LEASE_CAP_REACHED),
            // Manual-approval gate: an unapproved tier's cap is 0 — never acquire.
            Arguments.of(10L, 4L, 0L, 0L, AcquisitionPrecheckResult.LEASE_CAP_REACHED),

            // Under cap with waiting work: acquire.
            Arguments.of(10L, 4L, 4L, 5L, AcquisitionPrecheckResult.ACQUIRE),
            Arguments.of(10L, 1L, 0L, 1L, AcquisitionPrecheckResult.ACQUIRE),
            // No published cap (feature off / control index absent / dead monitor): unlimited.
            Arguments.of(10L, 4L, 1000L, null, AcquisitionPrecheckResult.ACQUIRE),

            // Work exists but none eligible here: other-tier work, or all of my tier leased.
            Arguments.of(10L, 0L, 0L, null, AcquisitionPrecheckResult.NO_WORK_AVAILABLE_FOR_TIER),
            Arguments.of(10L, 0L, 3L, 5L, AcquisitionPrecheckResult.NO_WORK_AVAILABLE_FOR_TIER),
            Arguments.of(10L, 0L, 3L, null, AcquisitionPrecheckResult.NO_WORK_AVAILABLE_FOR_TIER)
        );
    }

    @ParameterizedTest(name = "total={0} waiting={1} leased={2} cap={3} -> {4}")
    @MethodSource("decisionTable")
    void decisionTableHolds(long total, long waiting, long leased, Long cap,
                            AcquisitionPrecheckResult expected) {
        var census = new TierWorkCensus(total, waiting, leased);
        Assertions.assertEquals(expected, OpenSearchWorkCoordinator.decideAcquisition(census, cap));
    }
}
