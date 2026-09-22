/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.lifecycle;

import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

import lombok.NonNull;

/**
 * Runs required owner transitions on one mailbox and converts rejected or impossible transitions
 * into process-fatal failures.
 *
 * <p>The fatal-transition latch is owner-confined. A submission rejected before it reaches the
 * mailbox reports the process-fatal failure without mutating owner state from the submitting
 * thread.</p>
 */
final class OwnerTransitionRunner {
    private final ActorMailbox mailbox;
    private final OwnerThreadGuard ownerThreadGuard;
    private final String ownerIdentity;
    private final Consumer<Error> fatalHandler;
    private boolean fatalTransition;

    OwnerTransitionRunner(
        @NonNull ActorMailbox mailbox,
        @NonNull String ownerIdentity,
        @NonNull Consumer<Error> fatalHandler
    ) {
        this.mailbox = mailbox;
        this.ownerIdentity = ownerIdentity;
        this.fatalHandler = fatalHandler;
        this.ownerThreadGuard = new OwnerThreadGuard(
            "connection owner for " + ownerIdentity,
            mailbox::inMailbox
        );
    }

    void post(String operation, Runnable command) {
        post(operation, command, ignored -> {});
    }

    void post(
        String operation,
        Runnable command,
        Consumer<Throwable> transitionFailureHandler
    ) {
        try {
            mailbox.execute(() ->
                runTransition(operation, command, transitionFailureHandler)
            );
        } catch (RuntimeException | Error submissionFailure) {
            var failureToReport = notifyTransitionFailure(
                transitionFailureHandler,
                submissionFailure
            );
            reportSubmissionFailure(operation, failureToReport);
        }
    }

    void applyNowOrPost(String operation, Runnable command) {
        if (mailbox.inMailbox()) {
            runTransition(operation, command);
        } else {
            post(operation, command);
        }
    }

    void reportRejectedSubmission(String operation, RejectedExecutionException cause) {
        reportSubmissionFailure(operation, cause);
    }

    private void reportSubmissionFailure(
        String operation,
        Throwable failureToReport
    ) {
        if (mailbox.inMailbox()) {
            if (fatalTransition) {
                return;
            }
            fatalTransition = true;
        }
        reportFatal(
            "Required connection-owner submission failed during "
                + operation
                + " for "
                + ownerIdentity,
            failureToReport
        );
    }

    void reportImpossibleTransition(String operation, Throwable cause) {
        ownerThreadGuard.requireOwnerThread();
        fatalTransition = true;
        fatalHandler.accept(new Error(
            "Impossible connection-owner transition during "
                + operation
                + " for "
                + ownerIdentity,
            cause
        ));
    }

    /**
     * Reports an asynchronous cleanup failure without mutating the owner-confined fatal latch.
     *
     * <p>Completion callbacks may run either inline in the owner mailbox or on arbitrary threads
     * after the mailbox is unavailable, so this path cannot infer or change owner state.</p>
     */
    void reportCleanupFailure(String operation, Throwable cause) {
        fatalHandler.accept(new Error(
            "Connection-owner cleanup failed during "
                + operation
                + " for "
                + ownerIdentity,
            cause
        ));
    }

    void runTransition(String operation, Runnable command) {
        runTransition(operation, command, ignored -> {});
    }

    void runTransition(
        String operation,
        Runnable command,
        Consumer<Throwable> transitionFailureHandler
    ) {
        try {
            ownerThreadGuard.requireOwnerThread();
        } catch (Throwable confinementFailure) {
            fatalHandler.accept(new Error(
                "Connection-owner confinement invariant failed during "
                    + operation
                    + " for "
                    + ownerIdentity,
                notifyTransitionFailure(transitionFailureHandler, confinementFailure)
            ));
            return;
        }
        if (fatalTransition) {
            notifyTransitionFailure(
                transitionFailureHandler,
                new IllegalStateException(
                    "connection owner is no longer accepting transitions for " + ownerIdentity
                )
            );
            return;
        }
        try {
            command.run();
        } catch (Throwable failure) {
            fatalTransition = true;
            notifyTransitionFailure(transitionFailureHandler, failure);
            reportFatal(
                "Impossible connection-owner transition during "
                    + operation
                    + " for "
                    + ownerIdentity,
                failure
            );
        }
    }

    boolean fatalTransitionActive() {
        ownerThreadGuard.requireOwnerThread();
        return fatalTransition;
    }

    void requireOwnerThread() {
        ownerThreadGuard.requireOwnerThread();
    }

    private static Throwable notifyTransitionFailure(
        Consumer<Throwable> transitionFailureHandler,
        Throwable failure
    ) {
        try {
            transitionFailureHandler.accept(failure);
        } catch (Throwable callbackFailure) {
            if (callbackFailure != failure) {
                failure.addSuppressed(callbackFailure);
            }
        }
        return failure;
    }

    private void reportFatal(String message, Throwable failure) {
        if (failure instanceof Error error) {
            error.addSuppressed(new IllegalStateException(message));
            fatalHandler.accept(error);
        } else {
            fatalHandler.accept(new Error(message, failure));
        }
    }
}
