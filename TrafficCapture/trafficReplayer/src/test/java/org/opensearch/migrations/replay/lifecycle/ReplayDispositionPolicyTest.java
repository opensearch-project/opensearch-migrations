package org.opensearch.migrations.replay.lifecycle;

import java.util.concurrent.CancellationException;
import java.util.stream.Stream;

import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.EvidenceOutcome;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.SourceOutcome;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.TargetOutcome;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ReplayDispositionPolicyTest {
    private final ReplayDispositionPolicy policy = new ReplayDispositionPolicy();

    @ParameterizedTest
    @MethodSource("decisions")
    void everyTerminalCombinationHasAnExplicitDecision(
        SourceOutcome source,
        TargetOutcome<?> target,
        EvidenceOutcome evidence,
        Class<? extends RecordDisposition> expectedDisposition,
        boolean halt
    ) {
        var decision = policy.decide(source, target, evidence);
        Assertions.assertInstanceOf(expectedDisposition, decision.disposition());
        Assertions.assertEquals(halt, decision.haltReplay());
    }

    @Test
    void everyOutcomeVariantCombinationProducesADecision() {
        var sources = java.util.List.<SourceOutcome>of(
            new SourceOutcome.Complete(),
            new SourceOutcome.ConfirmedDead("proof"),
            new SourceOutcome.CapturedClose(),
            new SourceOutcome.Interrupted("rebalance"),
            new SourceOutcome.Shutdown("shutdown")
        );
        var targets = java.util.List.<TargetOutcome<String>>of(
            new TargetOutcome.Succeeded<>("response"),
            new TargetOutcome.Failed<>(new IllegalStateException("target")),
            new TargetOutcome.Cancelled<>(new CancellationException("cancelled")),
            new TargetOutcome.Filtered<>("filter")
        );
        var evidence = java.util.List.<EvidenceOutcome>of(
            new EvidenceOutcome.Durable("receipt"),
            new EvidenceOutcome.Failed(new IllegalStateException("sink")),
            new EvidenceOutcome.NotRequired("not-required")
        );

        int decisions = 0;
        for (var source : sources) {
            for (var target : targets) {
                for (var evidenceOutcome : evidence) {
                    Assertions.assertNotNull(policy.decide(source, target, evidenceOutcome).disposition());
                    decisions++;
                }
            }
        }
        Assertions.assertEquals(60, decisions);
    }

    private static Stream<Arguments> decisions() {
        return Stream.of(
            Arguments.of(
                new SourceOutcome.Complete(),
                new TargetOutcome.Succeeded<>("response"),
                new EvidenceOutcome.Durable("receipt"),
                RecordDisposition.Commit.class,
                false
            ),
            Arguments.of(
                new SourceOutcome.Complete(),
                new TargetOutcome.Filtered<>("filter"),
                new EvidenceOutcome.Durable("receipt"),
                RecordDisposition.Commit.class,
                false
            ),
            Arguments.of(
                new SourceOutcome.Complete(),
                new TargetOutcome.Failed<>(new IllegalStateException("target")),
                new EvidenceOutcome.Durable("receipt"),
                RecordDisposition.Retain.class,
                true
            ),
            Arguments.of(
                new SourceOutcome.Interrupted("rebalance"),
                new TargetOutcome.Cancelled<>(new CancellationException("rebalance")),
                new EvidenceOutcome.NotRequired("teardown"),
                RecordDisposition.Retain.class,
                false
            ),
            Arguments.of(
                new SourceOutcome.Shutdown("shutdown"),
                new TargetOutcome.Cancelled<>(new CancellationException("shutdown")),
                new EvidenceOutcome.NotRequired("teardown"),
                RecordDisposition.Retain.class,
                false
            ),
            Arguments.of(
                new SourceOutcome.Complete(),
                new TargetOutcome.Succeeded<>("response"),
                new EvidenceOutcome.Failed(new IllegalStateException("sink")),
                RecordDisposition.Retain.class,
                true
            ),
            Arguments.of(
                new SourceOutcome.ConfirmedDead("proof"),
                new TargetOutcome.Succeeded<>("response"),
                new EvidenceOutcome.NotRequired("missing"),
                RecordDisposition.Retain.class,
                true
            )
        );
    }
}
