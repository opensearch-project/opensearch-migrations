package org.opensearch.migrations.replay.lifecycle;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ReplayIntakeMailboxTest {
    @Test
    void ownerCommandsRunImmediatelyAndNestedCommandsStaySerialized() throws Exception {
        var mailbox = new ReplayIntakeMailbox();
        var order = new StringBuilder();

        mailbox.execute(() -> {
            order.append("a");
            mailbox.execute(() -> order.append("c"));
            order.append("b");
        });

        Assertions.assertEquals("ab", order.toString());
        mailbox.runUntilIdle();
        Assertions.assertEquals("abc", order.toString());
    }

    @Test
    void foreignCommandsWaitForTheOwnerToPumpThem() throws Exception {
        var mailbox = new ReplayIntakeMailbox();
        var ran = new AtomicBoolean();
        var thread = new Thread(() -> mailbox.execute(() -> ran.set(true)));

        thread.start();
        thread.join();
        Assertions.assertFalse(ran.get());

        mailbox.runUntilIdle();
        Assertions.assertTrue(ran.get());
    }

    @Test
    void awaitPumpsMailboxCommandsUntilTheOperationCompletes() throws Exception {
        var mailbox = new ReplayIntakeMailbox();
        var completion = new CompletableFuture<String>();
        var thread = new Thread(() -> mailbox.execute(() -> completion.complete("settled")));

        thread.start();
        Assertions.assertEquals("settled", mailbox.await(completion));
        thread.join();
    }

    @Test
    void completionFromAnotherThreadWakesAnIdleOwner() throws Exception {
        var mailbox = new ReplayIntakeMailbox();
        var completion = new CompletableFuture<String>();
        var thread = new Thread(() -> completion.complete("done"));

        thread.start();
        Assertions.assertEquals("done", mailbox.await(completion));
        thread.join();
    }

    @Test
    void queuedCommandFailureIsReportedOnTheOwnerThread() throws Exception {
        var mailbox = new ReplayIntakeMailbox();
        var expected = new IllegalStateException("broken");
        var thread = new Thread(() -> mailbox.execute(() -> {
            throw expected;
        }));

        thread.start();
        thread.join();

        var failure = Assertions.assertThrows(ExecutionException.class, mailbox::runUntilIdle);
        Assertions.assertSame(expected, failure.getCause());
    }

    @Test
    void pumpingFromAnotherThreadIsRejected() throws Exception {
        var mailbox = new ReplayIntakeMailbox();
        var failure = new AtomicReference<Throwable>();
        var thread = new Thread(() -> {
            try {
                mailbox.runUntilIdle();
            } catch (Throwable t) {
                failure.set(t);
            }
        });

        thread.start();
        thread.join();

        Assertions.assertInstanceOf(IllegalStateException.class, failure.get());
    }
}
