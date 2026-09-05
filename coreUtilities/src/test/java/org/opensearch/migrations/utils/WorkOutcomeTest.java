package org.opensearch.migrations.utils;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class WorkOutcomeTest {

    @Test
    void fromCreatesSucceededOutcome() {
        var outcome = WorkOutcome.from("value", null);

        Assertions.assertEquals("value", outcome.visit(new TestVisitor<>()));
    }

    @Test
    void fromCreatesFailedOutcomeAndUnwrapsCompletionWrappers() {
        var cause = new IllegalStateException("failed");
        var outcome = WorkOutcome.from(null, new CompletionException(new ExecutionException(cause)));

        Assertions.assertSame(cause, outcome.visit(new TestVisitor<>()));
    }

    @Test
    void fromCreatesCancelledOutcomeAndPreservesCause() {
        var cause = new CancellationException("cancelled");
        var outcome = WorkOutcome.from(null, new CompletionException(cause));

        Assertions.assertSame(cause, outcome.visit(new TestVisitor<>()));
    }

    @Test
    void outcomeCausesMustNotBeNull() {
        Assertions.assertThrows(NullPointerException.class, () -> new WorkOutcome.Failed<>(null));
        Assertions.assertThrows(NullPointerException.class, () -> new WorkOutcome.Cancelled<>(null));
    }

    private static class TestVisitor<T> implements WorkOutcome.Visitor<T, Object> {
        @Override
        public Object onSucceeded(WorkOutcome.Succeeded<T> outcome) {
            return outcome.value();
        }

        @Override
        public Object onFailed(WorkOutcome.Failed<T> outcome) {
            return outcome.cause();
        }

        @Override
        public Object onCancelled(WorkOutcome.Cancelled<T> outcome) {
            return outcome.cause();
        }
    }
}
