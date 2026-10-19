/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 *
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.migrations.utils.async;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AsyncLinkTest {

    @Test
    void normalHandlerOutputIsDeliveredToTheReceiver() throws Exception {
        AsyncLink<String, Integer, String, String> link =
            new AsyncLink<>((input, context) ->
                CompletableFuture.completedFuture(input.length() + context.length())
            );
        AsyncReceiver<Integer, String, String> receiver = (message, context) ->
            CompletableFuture.completedFuture(context + ":" + message);

        var result = link.receive("message", receiver, "context")
            .toCompletableFuture()
            .get(1, TimeUnit.SECONDS);

        Assertions.assertEquals("context:14", result);
    }

    @Test
    void synchronousHandlerFailureRemainsExceptionalAndSkipsTheReceiver() {
        var failure = new IllegalStateException("handler failed");
        var receiverInvoked = new AtomicBoolean();
        AsyncLink<String, Integer, String, String> link =
            new AsyncLink<>((input, context) -> {
                throw failure;
            });
        AsyncReceiver<Integer, String, String> receiver = (message, context) -> {
            receiverInvoked.set(true);
            return CompletableFuture.completedFuture("unexpected");
        };

        var thrown = Assertions.assertThrows(
            ExecutionException.class,
            () -> link.receive("message", receiver, "context")
                .toCompletableFuture()
                .get()
        );

        Assertions.assertSame(failure, thrown.getCause());
        Assertions.assertFalse(receiverInvoked.get());
    }

    @Test
    void exceptionalHandlerCompletionRemainsExceptionalAndSkipsTheReceiver() {
        var failure = new IllegalStateException("handler failed");
        var receiverInvoked = new AtomicBoolean();
        AsyncLink<String, Integer, String, String> link =
            new AsyncLink<>((input, context) -> CompletableFuture.failedFuture(failure));
        AsyncReceiver<Integer, String, String> receiver = (message, context) -> {
            receiverInvoked.set(true);
            return CompletableFuture.completedFuture("unexpected");
        };

        var thrown = Assertions.assertThrows(
            ExecutionException.class,
            () -> link.receive("message", receiver, "context")
                .toCompletableFuture()
                .get()
        );

        Assertions.assertSame(failure, thrown.getCause());
        Assertions.assertFalse(receiverInvoked.get());
    }

    @Test
    void receiverFailureRemainsExceptional() {
        var failure = new IllegalStateException("receiver failed");
        AsyncLink<String, Integer, String, String> link =
            new AsyncLink<>((input, context) -> CompletableFuture.completedFuture(input.length()));
        AsyncReceiver<Integer, String, String> receiver = (message, context) -> {
            throw failure;
        };

        var thrown = Assertions.assertThrows(
            ExecutionException.class,
            () -> link.receive("message", receiver, "context")
                .toCompletableFuture()
                .get()
        );

        Assertions.assertSame(failure, thrown.getCause());
    }

    @Test
    void nullHandlerOutputFailsBeforeReceiverInvocation() {
        var receiverInvoked = new AtomicBoolean();
        AsyncLink<String, Integer, String, String> link =
            new AsyncLink<>((input, context) -> CompletableFuture.completedFuture(null));
        AsyncReceiver<Integer, String, String> receiver = (message, context) -> {
            receiverInvoked.set(true);
            return CompletableFuture.completedFuture("unexpected");
        };

        var thrown = Assertions.assertThrows(
            ExecutionException.class,
            () -> link.receive("message", receiver, "context")
                .toCompletableFuture()
                .get()
        );

        Assertions.assertInstanceOf(NullPointerException.class, thrown.getCause());
        Assertions.assertFalse(receiverInvoked.get());
    }

    @Test
    void linkedStageWaitsForReceiverResult() {
        var receiverResult = new CompletableFuture<String>();
        AsyncLink<String, Integer, String, String> link =
            new AsyncLink<>((input, context) -> CompletableFuture.completedFuture(input.length()));
        AsyncReceiver<Integer, String, String> receiver = (message, context) -> receiverResult;

        var result = link.receive("message", receiver, "context").toCompletableFuture();

        Assertions.assertFalse(result.isDone());
        receiverResult.complete("complete");
        Assertions.assertEquals("complete", result.join());
    }
}
