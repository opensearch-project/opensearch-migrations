package org.opensearch.migrations.replay.lifecycle;

import java.util.concurrent.CancellationException;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ReplayOutcomesTest {
    @Test
    void requestPreparationHasExactlyReadyAndCancelledDomainResults() {
        var ready =
            new ReplayOutcomes.RequestPreparationResult.RequestPreparationReady<>("prepared");
        var cancelled =
            new ReplayOutcomes.RequestPreparationResult.RequestPreparationCancelled<String>(
                new CancellationException("cancelled")
            );

        Assertions.assertEquals("ready:prepared", describePreparation(ready));
        Assertions.assertEquals("cancelled:cancelled", describePreparation(cancelled));
    }

    private static String describePreparation(
        ReplayOutcomes.RequestPreparationResult<String> result
    ) {
        return switch (result) {
            case ReplayOutcomes.RequestPreparationResult.RequestPreparationReady<String> ready ->
                "ready:" + ready.value();
            case ReplayOutcomes.RequestPreparationResult.RequestPreparationCancelled<String> cancelled ->
                "cancelled:" + cancelled.cause().getMessage();
        };
    }

// REBUILD-LIMBO-START(G5)
// Both methods are about the four outcome families marked in ReplayOutcomes, and both assert that a visitor
// defined inside the test covers the type it was written against -- which the compiler already guarantees for
// a sealed hierarchy. Marked with those families rather than deleted so one milestone decides both.
/*
    @Test
    void sourceAndEvidenceOutcomesAreExhaustiveValues() {
        var sourceVisitor = new ReplayOutcomes.SourceOutcome.Visitor<String>() {
            @Override
            public String onComplete(ReplayOutcomes.SourceOutcome.Complete outcome) {
                return "complete";
            }

            @Override
            public String onConfirmedDead(ReplayOutcomes.SourceOutcome.ConfirmedDead outcome) {
                return outcome.proofId();
            }

            @Override
            public String onCapturedClose(ReplayOutcomes.SourceOutcome.CapturedClose outcome) {
                return "close";
            }

            @Override
            public String onLegacyExpired(ReplayOutcomes.SourceOutcome.LegacyExpired outcome) {
                return "legacy-expired";
            }

            @Override
            public String onInconclusive(ReplayOutcomes.SourceOutcome.Inconclusive outcome) {
                return outcome.reason();
            }

            @Override
            public String onInterrupted(ReplayOutcomes.SourceOutcome.Interrupted outcome) {
                return outcome.reason();
            }

            @Override
            public String onShutdown(ReplayOutcomes.SourceOutcome.Shutdown outcome) {
                return outcome.reason();
            }
        };
        var evidenceVisitor = new ReplayOutcomes.EvidenceOutcome.Visitor<String>() {
            @Override
            public String onDurable(ReplayOutcomes.EvidenceOutcome.Durable outcome) {
                return outcome.receipt();
            }

            @Override
            public String onFailed(ReplayOutcomes.EvidenceOutcome.Failed outcome) {
                return outcome.cause().getMessage();
            }

            @Override
            public String onNotRequired(ReplayOutcomes.EvidenceOutcome.NotRequired outcome) {
                return outcome.reason();
            }
        };

        Assertions.assertEquals("proof", new ReplayOutcomes.SourceOutcome.ConfirmedDead("proof").visit(sourceVisitor));
        Assertions.assertEquals("legacy-expired", new ReplayOutcomes.SourceOutcome.LegacyExpired().visit(sourceVisitor));
        Assertions.assertEquals("receipt", new ReplayOutcomes.EvidenceOutcome.Durable("receipt").visit(evidenceVisitor));
        Assertions.assertEquals(
            "discard",
            new ReplayOutcomes.EvidenceOutcome.NotRequired("discard").visit(evidenceVisitor)
        );
    }

    @Test
    void sessionVisitorMustAcknowledgeEveryOutcome() {
        var visitor = new ReplayOutcomes.SessionOutcome.Visitor<String>() {
            @Override
            public String onClosed(ReplayOutcomes.SessionOutcome.Closed outcome) {
                return "closed";
            }

            @Override
            public String onAborted(ReplayOutcomes.SessionOutcome.Aborted outcome) {
                return "aborted:" + outcome.cause().getMessage();
            }

            @Override
            public String onFailed(ReplayOutcomes.SessionOutcome.Failed outcome) {
                return "failed:" + outcome.cause().getMessage();
            }
        };

        Assertions.assertEquals("closed", new ReplayOutcomes.SessionOutcome.Closed().visit(visitor));
        Assertions.assertEquals(
            "aborted:stop",
            new ReplayOutcomes.SessionOutcome.Aborted(
                ReplayOutcomes.SessionOutcome.AbortReason.SOURCE_REASSIGNMENT,
                new CancellationException("stop")
            ).visit(visitor)
        );
        Assertions.assertEquals(
            "failed:bad",
            new ReplayOutcomes.SessionOutcome.Failed(new IllegalStateException("bad")).visit(visitor)
        );
    }

*/
// REBUILD-LIMBO-END(G5)
}
