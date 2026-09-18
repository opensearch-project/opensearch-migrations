package org.opensearch.migrations.replay;

import org.opensearch.migrations.replay.lifecycle.RecordDisposition;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.SourceOutcome;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SourceReconstructionPolicyTest {
    @Test
    void structuralSourceTimeoutIsInconclusiveAndRetained() {
        var policy = new SourceReconstructionPolicy(true);
        var status = RequestResponsePacketPair.ReconstructionStatus.EXPIRED_PREMATURELY;

        Assertions.assertInstanceOf(SourceOutcome.Inconclusive.class, policy.classify(status, null));
        Assertions.assertInstanceOf(
            RecordDisposition.Retain.class,
            policy.sourceOnlyDisposition(status, "expiry")
        );
    }

    @Test
    void legacySourceTimeoutRemainsAnExplicitCommitEligibleOutcome() {
        var policy = new SourceReconstructionPolicy(false);
        var status = RequestResponsePacketPair.ReconstructionStatus.EXPIRED_PREMATURELY;

        Assertions.assertInstanceOf(SourceOutcome.LegacyExpired.class, policy.classify(status, null));
        Assertions.assertInstanceOf(
            RecordDisposition.Commit.class,
            policy.sourceOnlyDisposition(status, "expiry")
        );
    }
}
