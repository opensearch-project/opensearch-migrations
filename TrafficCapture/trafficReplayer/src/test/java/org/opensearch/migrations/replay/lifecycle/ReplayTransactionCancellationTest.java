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

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ConnectionSessionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ReplayRequestId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourceConnectionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.SourceOutcome;
import org.opensearch.migrations.replay.testing.TestEventLoop;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ReplayTransactionCancellationTest {

    @Test
    void cancellationWinsForALiveTransactionAndPreservesItsCause() throws Exception {
        var mailbox = new TestEventLoop();
        var transaction = new ReplayTransaction<String>(
            new ReplayRequestId(
                new ConnectionSessionKey(
                    new SourceConnectionKey("node", "connection"),
                    0,
                    0
                ),
                0
            ),
            mailbox,
            (ignoredRequest, ignoredSource, ignoredTarget) ->
                Assertions.fail("an unsettled transaction cannot write evidence"),
            List.of()
        );
        var cancellation = new CancellationException("shutdown");

        var cancellationAcknowledgement = transaction.requestCancellation(cancellation);
        mailbox.runUntilIdle();

        Assertions.assertInstanceOf(
            ReplayOutcomes.ProcessingCancellationResult.CancellationWon.class,
            cancellationAcknowledgement.toCompletableFuture().get(5, TimeUnit.SECONDS)
        );
        var observedFailure = Assertions.assertThrows(
            ExecutionException.class,
            () -> transaction.completion().toCompletableFuture().get(5, TimeUnit.SECONDS)
        );
        Assertions.assertSame(cancellation, observedFailure.getCause());
    }

    @Test
    void cancellationPreemptsEvidenceThatIsAlreadyInFlight() throws Exception {
        var mailbox = new TestEventLoop();
        var evidence = new CompletableFuture<ReplayOutcomes.EvidenceOutcome>();
        var transaction = new ReplayTransaction<String>(
            new ReplayRequestId(
                new ConnectionSessionKey(
                    new SourceConnectionKey("node", "connection"),
                    0,
                    0
                ),
                0
            ),
            mailbox,
            (ignoredRequest, ignoredSource, ignoredTarget) -> evidence,
            List.of()
        );
        var cancellation = new CancellationException("shutdown");

        transaction.settleSource(new SourceOutcome.Complete());
        transaction.settleTargetResult("response");
        mailbox.runUntilIdle();
        Assertions.assertFalse(evidence.isDone());

        var cancellationAcknowledgement = transaction.requestCancellation(cancellation);
        mailbox.runUntilIdle();

        Assertions.assertInstanceOf(
            ReplayOutcomes.ProcessingCancellationResult.CancellationWon.class,
            cancellationAcknowledgement.toCompletableFuture().get(5, TimeUnit.SECONDS)
        );
        var observedFailure = Assertions.assertThrows(
            ExecutionException.class,
            () -> transaction.completion().toCompletableFuture().get(5, TimeUnit.SECONDS)
        );
        Assertions.assertSame(cancellation, observedFailure.getCause());

        evidence.complete(new ReplayOutcomes.EvidenceOutcome.Durable("discarded tuple"));
        mailbox.runUntilIdle();
        var failureAfterEvidence = Assertions.assertThrows(
            ExecutionException.class,
            () -> transaction.completion().toCompletableFuture().get(5, TimeUnit.SECONDS)
        );
        Assertions.assertSame(cancellation, failureAfterEvidence.getCause());
    }

    @Test
    void cancellationAcknowledgesTargetCancellationThatWinsTheMailboxRace() throws Exception {
        var mailbox = new TestEventLoop();
        var requestId = new ReplayRequestId(
            new ConnectionSessionKey(
                new SourceConnectionKey("node", "connection"),
                0,
                0
            ),
            0
        );
        var transaction = new ReplayTransaction<String>(
            requestId,
            mailbox,
            (ignoredRequest, ignoredSource, ignoredTarget) ->
                Assertions.fail("cancelled target settlement must not write evidence"),
            List.of()
        );
        var cancellation = new CancellationException("shutdown");

        var sourceAcknowledgement = transaction.settleSource(new SourceOutcome.Complete());
        var targetAcknowledgement = transaction.settleTargetCancellation(cancellation);
        var cancellationAcknowledgement = transaction.requestCancellation(cancellation);

        mailbox.runUntilIdle();

        sourceAcknowledgement.toCompletableFuture().get(5, TimeUnit.SECONDS);
        targetAcknowledgement.toCompletableFuture().get(5, TimeUnit.SECONDS);
        Assertions.assertInstanceOf(
            ReplayOutcomes.ProcessingCancellationResult.CancellationWon.class,
            cancellationAcknowledgement.toCompletableFuture().get(5, TimeUnit.SECONDS)
        );
        var outcome = transaction.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
        Assertions.assertNull(outcome.targetResult());
        Assertions.assertSame(cancellation, outcome.targetCancellation());
        Assertions.assertInstanceOf(
            ReplayOutcomes.EvidenceOutcome.NotRequired.class,
            outcome.evidenceOutcome()
        );
    }

    @Test
    void cancellationReportsThatAnAlreadyDurableNormalTransactionWon() throws Exception {
        var mailbox = new TestEventLoop();
        var requestId = new ReplayRequestId(
            new ConnectionSessionKey(
                new SourceConnectionKey("node", "connection"),
                0,
                0
            ),
            0
        );
        var targetResult = new Object();
        var transaction = new ReplayTransaction<Object>(
            requestId,
            mailbox,
            (ignoredRequest, ignoredSource, observedTarget) -> {
                Assertions.assertSame(targetResult, observedTarget);
                return java.util.concurrent.CompletableFuture.completedFuture(
                    new ReplayOutcomes.EvidenceOutcome.Durable("test tuple")
                );
            },
            List.of()
        );

        transaction.settleSource(new SourceOutcome.Complete());
        transaction.settleTargetResult(targetResult);
        mailbox.runUntilIdle();
        var outcome = transaction.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
        Assertions.assertSame(targetResult, outcome.targetResult());
        Assertions.assertNull(outcome.targetCancellation());
        Assertions.assertInstanceOf(
            ReplayOutcomes.EvidenceOutcome.Durable.class,
            outcome.evidenceOutcome()
        );

        var cancellation = transaction.requestCancellation(
            new CancellationException("too late")
        );
        Assertions.assertInstanceOf(
            ReplayOutcomes.ProcessingCancellationResult.ProcessingCompletionWon.class,
            cancellation.toCompletableFuture().get(5, TimeUnit.SECONDS)
        );
    }

    @Test
    void transactionOutcomeRequiresExactlyOneTargetSettlement() {
        var requestId = new ReplayRequestId(
            new ConnectionSessionKey(
                new SourceConnectionKey("node", "connection"),
                0,
                0
            ),
            0
        );
        var source = new SourceOutcome.Complete();
        var evidence = new ReplayOutcomes.EvidenceOutcome.Durable("test tuple");
        var cancellation = new CancellationException("shutdown");

        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new ReplayTransaction.TransactionOutcome<String>(
                requestId,
                source,
                null,
                null,
                evidence
            )
        );
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new ReplayTransaction.TransactionOutcome<>(
                requestId,
                source,
                "response",
                cancellation,
                evidence
            )
        );
    }

    @Test
    void cancellationReportsNormalCompletionQueuedAheadOfItsMailboxCommand() throws Exception {
        var mailbox = new TestEventLoop();
        var evidence = new CompletableFuture<ReplayOutcomes.EvidenceOutcome>();
        var transaction = new ReplayTransaction<String>(
            new ReplayRequestId(
                new ConnectionSessionKey(
                    new SourceConnectionKey("node", "connection"),
                    0,
                    0
                ),
                0
            ),
            mailbox,
            (ignoredRequest, ignoredSource, ignoredTarget) -> evidence,
            List.of()
        );

        transaction.settleSource(new SourceOutcome.Complete());
        transaction.settleTargetResult("response");
        mailbox.runUntilIdle();

        evidence.complete(new ReplayOutcomes.EvidenceOutcome.Durable("test tuple"));
        var cancellation = transaction.requestCancellation(
            new CancellationException("normal completion already queued")
        );
        mailbox.runUntilIdle();

        var outcome = transaction.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
        Assertions.assertInstanceOf(
            ReplayOutcomes.EvidenceOutcome.Durable.class,
            outcome.evidenceOutcome()
        );
        Assertions.assertInstanceOf(
            ReplayOutcomes.ProcessingCancellationResult.ProcessingCompletionWon.class,
            cancellation.toCompletableFuture().get(5, TimeUnit.SECONDS)
        );
    }

    @Test
    void cancellationPreservesAnAlreadyReservedProcessingFailure() throws Exception {
        var mailbox = new TestEventLoop();
        var requestId = new ReplayRequestId(
            new ConnectionSessionKey(
                new SourceConnectionKey("node", "connection"),
                0,
                0
            ),
            0
        );
        var transaction = new ReplayTransaction<String>(
            requestId,
            mailbox,
            (ignoredRequest, ignoredSource, ignoredTarget) ->
                Assertions.fail("failed transaction must not write evidence"),
            List.of()
        );
        var processingFailure = new IllegalStateException("processing failed");

        transaction.fail(processingFailure);
        var cancellation = transaction.requestCancellation(
            new CancellationException("shutdown")
        );

        Assertions.assertInstanceOf(
            ReplayOutcomes.ProcessingCancellationResult.ProcessingCompletionWon.class,
            cancellation.toCompletableFuture().get(5, TimeUnit.SECONDS)
        );
        mailbox.runUntilIdle();
        var observedFailure = Assertions.assertThrows(
            ExecutionException.class,
            () -> transaction.completion().toCompletableFuture().get(5, TimeUnit.SECONDS)
        );
        Assertions.assertSame(processingFailure, observedFailure.getCause());
    }
}

*/
// REBUILD-LIMBO-END(G11)