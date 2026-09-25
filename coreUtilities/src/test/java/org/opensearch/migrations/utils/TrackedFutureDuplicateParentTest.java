package org.opensearch.migrations.utils;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TrackedFutureDuplicateParentTest {

    @Test
    void setParentDiagnosticFuture_duplicateParentDoesNotThrow() {
        var child = new TrackedFuture<>(new CompletableFuture<String>(), () -> "child");
        var parentA = new TrackedFuture<>(CompletableFuture.completedFuture("a"), () -> "parentA");
        var parentB = new TrackedFuture<>(CompletableFuture.completedFuture("b"), () -> "parentB");

        child.setParentDiagnosticFuture(parentA);
        Assertions.assertDoesNotThrow(() -> child.setParentDiagnosticFuture(parentB));
    }

    @Test
    void setParentDiagnosticFuture_duplicateParentRetainsOriginalParent() {
        var child = new TrackedFuture<>(new CompletableFuture<String>(), () -> "child");
        var parentA = new TrackedFuture<>(CompletableFuture.completedFuture("a"), () -> "parentA");
        var parentB = new TrackedFuture<>(CompletableFuture.completedFuture("b"), () -> "parentB");

        child.setParentDiagnosticFuture(parentA);
        child.setParentDiagnosticFuture(parentB);

        Assertions.assertSame(parentA, child.getParentDiagnosticFuture());
    }
}
