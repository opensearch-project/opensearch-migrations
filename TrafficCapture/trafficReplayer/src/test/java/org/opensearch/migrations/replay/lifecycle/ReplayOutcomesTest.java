package org.opensearch.migrations.replay.lifecycle;

import java.util.concurrent.CancellationException;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ReplayOutcomesTest {
    @Test
    void targetVisitorMustAcknowledgeEveryOutcome() {
        ReplayOutcomes.TargetOutcome.Visitor<String, String> visitor = new ReplayOutcomes.TargetOutcome.Visitor<>() {
            @Override
            public String onSucceeded(ReplayOutcomes.TargetOutcome.Succeeded<String> outcome) {
                return "success:" + outcome.value();
            }

            @Override
            public String onFailed(ReplayOutcomes.TargetOutcome.Failed<String> outcome) {
                return "failure:" + outcome.cause().getMessage();
            }

            @Override
            public String onCancelled(ReplayOutcomes.TargetOutcome.Cancelled<String> outcome) {
                return "cancelled:" + outcome.cause().getMessage();
            }

            @Override
            public String onFiltered(ReplayOutcomes.TargetOutcome.Filtered<String> outcome) {
                return "filtered:" + outcome.reason();
            }
        };

        Assertions.assertEquals("success:value", new ReplayOutcomes.TargetOutcome.Succeeded<>("value").visit(visitor));
        Assertions.assertEquals(
            "failure:bad",
            new ReplayOutcomes.TargetOutcome.Failed<String>(new IllegalStateException("bad")).visit(visitor)
        );
        Assertions.assertEquals(
            "cancelled:stop",
            new ReplayOutcomes.TargetOutcome.Cancelled<String>(new CancellationException("stop")).visit(visitor)
        );
        Assertions.assertEquals(
            "filtered:policy",
            new ReplayOutcomes.TargetOutcome.Filtered<String>("policy").visit(visitor)
        );
    }

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
            new ReplayOutcomes.SessionOutcome.Aborted(new CancellationException("stop")).visit(visitor)
        );
        Assertions.assertEquals(
            "failed:bad",
            new ReplayOutcomes.SessionOutcome.Failed(new IllegalStateException("bad")).visit(visitor)
        );
    }
}
