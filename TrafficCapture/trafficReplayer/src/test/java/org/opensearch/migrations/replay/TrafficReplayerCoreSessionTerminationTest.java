package org.opensearch.migrations.replay;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.SessionOutcome;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.SessionOutcome.AbortReason;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TrafficReplayerCoreSessionTerminationTest {

    @Test
    void sourceReassignmentMaySupersedeAnAlreadyQueuedCapturedClose() {
        var actorTermination = TrafficReplayerCore.acceptOrderedCloseOutcome(
            new SessionOutcome.Aborted(
                AbortReason.SOURCE_REASSIGNMENT,
                new java.util.concurrent.CancellationException("source reassigned")
            )
        );

        Assertions.assertDoesNotThrow(
            () -> TrafficReplayerCore.combineConnectionCloseOperations(
                CompletableFuture.completedFuture(null),
                actorTermination
            )
                .get()
        );
    }

    @Test
    void sourceDispositionFailureIsNotHiddenByExpectedActorCancellation() {
        var sourceFailure = new IllegalStateException("source disposition failed");
        var completion = TrafficReplayerCore.combineConnectionCloseOperations(
            CompletableFuture.failedFuture(sourceFailure),
            TrafficReplayerCore.acceptOrderedCloseOutcome(
                new SessionOutcome.Aborted(
                    AbortReason.SOURCE_REASSIGNMENT,
                    new java.util.concurrent.CancellationException("source reassigned")
                )
            )
        );

        var error = Assertions.assertThrows(ExecutionException.class, completion::get);
        Assertions.assertSame(sourceFailure, error.getCause());
    }

    @Test
    void ordinaryActorTerminationFailureRemainsFatal() {
        var actorFailure = new IllegalStateException("actor failed");
        var completion = TrafficReplayerCore.combineConnectionCloseOperations(
            CompletableFuture.completedFuture(null),
            TrafficReplayerCore.acceptOrderedCloseOutcome(new SessionOutcome.Failed(actorFailure))
        );

        var error = Assertions.assertThrows(ExecutionException.class, completion::get);
        Assertions.assertSame(actorFailure, error.getCause());
    }

    @Test
    void dependencyCancellationOfAnOrderedCloseRemainsFatal() {
        var actorFailure = new java.util.concurrent.CancellationException("dependency cancelled");
        var completion = TrafficReplayerCore.acceptOrderedCloseOutcome(
            new SessionOutcome.Aborted(AbortReason.DEPENDENCY_CANCELLED, actorFailure)
        );

        var error = Assertions.assertThrows(
            java.util.concurrent.CancellationException.class,
            () -> completion.toCompletableFuture().get()
        );
        Assertions.assertSame(actorFailure, error);
    }
}
