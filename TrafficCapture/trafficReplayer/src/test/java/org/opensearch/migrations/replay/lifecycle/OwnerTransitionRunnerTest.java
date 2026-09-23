/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.lifecycle;

// REBUILD-LIMBO(G11) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.

// REBUILD-LIMBO-START(G11)
/*

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.opensearch.migrations.replay.testing.TestEventLoop;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class OwnerTransitionRunnerTest {

    @Test
    void postRunsTheTransitionOnlyInsideTheMailbox() {
        var mailbox = new TestEventLoop();
        var fatalFailures = new ArrayList<Error>();
        var runner = new OwnerTransitionRunner(mailbox, "session", fatalFailures::add);
        var transitionRan = new AtomicBoolean();

        runner.post("test transition", () -> {
            Assertions.assertTrue(mailbox.inMailbox());
            transitionRan.set(true);
        });

        Assertions.assertFalse(transitionRan.get());
        mailbox.runUntilIdle();
        Assertions.assertTrue(transitionRan.get());
        Assertions.assertTrue(fatalFailures.isEmpty());
    }

    @Test
    void rejectedSubmissionReportsFatalAndNotifiesTheCaller() {
        var mailbox = new TestEventLoop();
        var fatalFailures = new ArrayList<Error>();
        var transitionFailure = new AtomicReference<Throwable>();
        var runner = new OwnerTransitionRunner(mailbox, "session", fatalFailures::add);
        mailbox.rejectNewTasks();

        runner.post("rejected transition", Assertions::fail, transitionFailure::set);

        Assertions.assertEquals(1, fatalFailures.size());
        Assertions.assertTrue(
            fatalFailures.get(0).getMessage().contains("rejected transition")
        );
        Assertions.assertInstanceOf(
            RejectedExecutionException.class,
            fatalFailures.get(0).getCause()
        );
        Assertions.assertSame(fatalFailures.get(0).getCause(), transitionFailure.get());
    }

    @Test
    void impossibleTransitionLatchesTheOwnerAndRejectsLaterWork() {
        var mailbox = new TestEventLoop();
        var fatalFailures = new ArrayList<Error>();
        var firstFailure = new IllegalStateException("invalid state");
        var observedFirstFailure = new AtomicReference<Throwable>();
        var observedLaterFailure = new AtomicReference<Throwable>();
        var laterTransitionRan = new AtomicBoolean();
        var runner = new OwnerTransitionRunner(mailbox, "session", fatalFailures::add);

        runner.post(
            "invalid transition",
            () -> {
                throw firstFailure;
            },
            observedFirstFailure::set
        );
        runner.post(
            "later transition",
            () -> laterTransitionRan.set(true),
            observedLaterFailure::set
        );
        mailbox.runUntilIdle();

        Assertions.assertSame(firstFailure, observedFirstFailure.get());
        Assertions.assertEquals(1, fatalFailures.size());
        Assertions.assertSame(firstFailure, fatalFailures.get(0).getCause());
        Assertions.assertFalse(laterTransitionRan.get());
        Assertions.assertInstanceOf(
            IllegalStateException.class,
            observedLaterFailure.get()
        );
        Assertions.assertTrue(
            observedLaterFailure.get().getMessage().contains("no longer accepting")
        );
    }

    @Test
    void externalCleanupFailureDoesNotMutateTheOwnerLatch() {
        var mailbox = new TestEventLoop();
        var fatalFailures = new ArrayList<Error>();
        var runner = new OwnerTransitionRunner(mailbox, "session", fatalFailures::add);
        var cleanupFailure = new IllegalStateException("cleanup failed");
        var laterTransitionRan = new AtomicBoolean();

        runner.reportCleanupFailure("rejected cleanup", cleanupFailure);
        runner.post("later transition", () -> laterTransitionRan.set(true));
        mailbox.runUntilIdle();

        Assertions.assertEquals(1, fatalFailures.size());
        Assertions.assertSame(cleanupFailure, fatalFailures.get(0).getCause());
        Assertions.assertTrue(laterTransitionRan.get());
    }

    @Test
    void errorTransitionPreservesTheErrorAndLatchesTheOwner() {
        var mailbox = new TestEventLoop();
        var fatalFailures = new ArrayList<Error>();
        var transitionFailure = new AtomicReference<Throwable>();
        var laterTransitionFailure = new AtomicReference<Throwable>();
        var laterTransitionRan = new AtomicBoolean();
        var fatalError = new AssertionError("fatal invariant");
        var runner = new OwnerTransitionRunner(mailbox, "session", fatalFailures::add);

        runner.post(
            "fatal transition",
            () -> {
                throw fatalError;
            },
            transitionFailure::set
        );
        runner.post(
            "later transition",
            () -> laterTransitionRan.set(true),
            laterTransitionFailure::set
        );
        mailbox.runUntilIdle();

        Assertions.assertSame(fatalError, transitionFailure.get());
        Assertions.assertEquals(1, fatalFailures.size());
        Assertions.assertSame(fatalError, fatalFailures.get(0));
        Assertions.assertEquals(1, fatalError.getSuppressed().length);
        Assertions.assertTrue(
            fatalError.getSuppressed()[0].getMessage().contains("fatal transition")
        );
        Assertions.assertTrue(
            fatalError.getSuppressed()[0].getMessage().contains("session")
        );
        Assertions.assertFalse(laterTransitionRan.get());
        Assertions.assertInstanceOf(
            IllegalStateException.class,
            laterTransitionFailure.get()
        );
        Assertions.assertTrue(
            laterTransitionFailure.get().getMessage().contains("no longer accepting")
        );
    }

    @Test
    void failureHandlerCannotSuppressFatalReporting() {
        var mailbox = new TestEventLoop();
        var fatalFailures = new ArrayList<Error>();
        var transitionFailure = new IllegalStateException("invalid transition");
        var callbackFailure = new AssertionError("cleanup callback failed");
        var runner = new OwnerTransitionRunner(mailbox, "session", fatalFailures::add);

        runner.post(
            "invalid transition",
            () -> {
                throw transitionFailure;
            },
            ignored -> {
                throw callbackFailure;
            }
        );
        mailbox.runUntilIdle();

        Assertions.assertEquals(1, fatalFailures.size());
        Assertions.assertSame(transitionFailure, fatalFailures.get(0).getCause());
        Assertions.assertArrayEquals(
            new Throwable[] {callbackFailure},
            transitionFailure.getSuppressed()
        );
    }

    @Test
    void confinementFailureReachesFatalHandlerWithoutRunningTheCommand() {
        var mailbox = new TestEventLoop();
        var fatalFailures = new ArrayList<Error>();
        var transitionFailure = new AtomicReference<Throwable>();
        var transitionRan = new AtomicBoolean();
        var runner = new OwnerTransitionRunner(mailbox, "session", fatalFailures::add);

        runner.runTransition(
            "outside mailbox",
            () -> transitionRan.set(true),
            transitionFailure::set
        );

        Assertions.assertFalse(transitionRan.get());
        Assertions.assertInstanceOf(
            IllegalStateException.class,
            transitionFailure.get()
        );
        Assertions.assertEquals(1, fatalFailures.size());
        Assertions.assertSame(transitionFailure.get(), fatalFailures.get(0).getCause());
    }

    @Test
    void unexpectedSynchronousSubmissionFailureReachesFatalHandler() {
        var submissionFailure = new IllegalStateException("executor failed");
        var mailbox = new ActorMailbox() {
            @Override
            public void execute(Runnable command) {
                throw submissionFailure;
            }

            @Override
            public boolean inMailbox() {
                return true;
            }

            @Override
            public Instant now() {
                return Instant.EPOCH;
            }

            @Override
            public ScheduledTask schedule(Runnable command, Duration delay) {
                throw submissionFailure;
            }
        };
        var fatalFailures = new ArrayList<Error>();
        var transitionFailure = new AtomicReference<Throwable>();
        var laterTransitionRan = new AtomicBoolean();
        var runner = new OwnerTransitionRunner(mailbox, "session", fatalFailures::add);

        runner.post(
            "failed submission",
            Assertions::fail,
            transitionFailure::set
        );

        Assertions.assertSame(submissionFailure, transitionFailure.get());
        Assertions.assertEquals(1, fatalFailures.size());
        Assertions.assertSame(submissionFailure, fatalFailures.get(0).getCause());
        runner.applyNowOrPost(
            "later transition",
            () -> laterTransitionRan.set(true)
        );
        Assertions.assertFalse(laterTransitionRan.get());
    }
}

*/
// REBUILD-LIMBO-END(G11)