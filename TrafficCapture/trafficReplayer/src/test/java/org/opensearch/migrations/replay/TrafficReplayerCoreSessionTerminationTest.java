package org.opensearch.migrations.replay;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TrafficReplayerCoreSessionTerminationTest {

    @Test
    void sourceReassignmentMaySupersedeAnAlreadyQueuedCapturedClose() {
        var actorTermination = CompletableFuture.<Void>failedFuture(
            new SourceReassignmentCancellationException("source reassigned")
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
            CompletableFuture.failedFuture(
                new SourceReassignmentCancellationException("source reassigned")
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
            CompletableFuture.failedFuture(actorFailure)
        );

        var error = Assertions.assertThrows(ExecutionException.class, completion::get);
        Assertions.assertSame(actorFailure, error.getCause());
    }
}
