package org.opensearch.migrations.replay.lifecycle;

import java.util.List;
import java.util.concurrent.CancellationException;

import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.TargetAttemptOutcome;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ReplayOutcomesTest {
    @Test
    void requestPreparationHasExactlyReadyAndCancelledDomainResults() {
        var ready =
            new ReplayOutcomes.RequestPreparationReady<>("prepared");
        var cancelled =
            new ReplayOutcomes.RequestPreparationCancelled<String>(
                new CancellationException("cancelled")
            );

        Assertions.assertEquals("ready:prepared", describePreparation(ready));
        Assertions.assertEquals("cancelled:cancelled", describePreparation(cancelled));
        var filtered = new ReplayOutcomes.RequestPreparationReady<String>(
            null,
            HttpRequestTransformationStatus.skipped()
        );
        Assertions.assertTrue(filtered.transformationStatus().isSkipped());
        Assertions.assertNull(filtered.value());
    }

    @Test
    void transformationFallbackRemainsAReplayableReadyAndTupleResult() {
        var transformationFailure = new IllegalArgumentException("use original request");
        var errorStatus = HttpRequestTransformationStatus.makeError(transformationFailure);
        var ready = new ReplayOutcomes.RequestPreparationReady<>("fallback", errorStatus);
        var terminal = new TargetAttemptOutcome.TargetResponseObtained<>("target response");

        var result = new RequestReplayOwner.RequestResult<>(
            TargetConnectionOwnerTestSupport.request(1),
            "source request",
            ready.value(),
            ready.transformationStatus(),
            List.of(terminal),
            terminal,
            new RequestReplayOwner.CompleteFinalSourceResponse<>("source response", true)
        );

        Assertions.assertSame(transformationFailure, result.transformationStatus().getException());
        Assertions.assertEquals("fallback", result.preparedRequest());
        Assertions.assertSame(terminal, result.terminalTargetResponse());
    }

    private static String describePreparation(
        ReplayOutcomes.RequestPreparationResult<String> result
    ) {
        return switch (result) {
            case ReplayOutcomes.RequestPreparationReady<String> ready ->
                "ready:" + ready.value();
            case ReplayOutcomes.RequestPreparationCancelled<String> cancelled ->
                "cancelled:" + cancelled.cause().getMessage();
        };
    }

}
