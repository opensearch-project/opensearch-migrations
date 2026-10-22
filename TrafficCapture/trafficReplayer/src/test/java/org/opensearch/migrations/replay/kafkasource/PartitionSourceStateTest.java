/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.kafkasource;

import org.opensearch.migrations.replay.identity.PartitionBatchRequestId;
import org.opensearch.migrations.replay.identity.PartitionGenerationId;

import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Covers the demand and pause-reason cases in {@code kafkaLLD §17.4}. */
class PartitionSourceStateTest {

    private static final PartitionGenerationId GENERATION =
        new PartitionGenerationId(new TopicPartition("traffic", 3), 7);

    @Test
    void assignmentBootstrapMakesThePartitionReadableBeforeAnExplicitBatchIsRequested() {
        var state = new PartitionSourceState(GENERATION);

        Assertions.assertTrue(state.isReadable(), "assignment installs one source-local bootstrap entitlement");
        Assertions.assertTrue(state.isAssignmentBootstrapPending());
        Assertions.assertTrue(state.isKafkaPaused(), "a new generation starts paused");

        state.requestBatch(new PartitionBatchRequestId(GENERATION, 1));

        Assertions.assertTrue(state.isReadable(), "the explicit entitlement may overlap bootstrap");
    }

    /**
     * The three reasons are independent and must not alias: clearing one cannot resume a partition another
     * still prohibits. Each is cleared by a different message from a different owner, so aliasing them is
     * how a partition reads when it must not.
     */
    @Test
    void theThreePauseReasonsAreIndependent() {
        var state = new PartitionSourceState(GENERATION);
        state.requestBatch(new PartitionBatchRequestId(GENERATION, 1));
        state.setPriorGenerationCleanupPending(true);
        state.endIntake();

        Assertions.assertFalse(state.isReadable());

        state.setPriorGenerationCleanupPending(false);
        Assertions.assertFalse(state.isReadable(), "cleanup finishing must not override ended intake");

        var reopened = new PartitionSourceState(GENERATION);
        reopened.setPriorGenerationCleanupPending(true);
        reopened.requestBatch(new PartitionBatchRequestId(GENERATION, 1));
        Assertions.assertFalse(reopened.isReadable(), "a request must not override pending cleanup");
        reopened.setPriorGenerationCleanupPending(false);
        Assertions.assertTrue(reopened.isReadable());
    }

    /**
     * Prior-generation cleanup delays reading without rejecting the request: it stays outstanding and the
     * partition stays paused until cleanup finishes ({@code kafkaLLD §5.3}).
     */
    @Test
    void cleanupDoesNotRejectTheOutstandingRequest() {
        var state = new PartitionSourceState(GENERATION);
        var requestId = new PartitionBatchRequestId(GENERATION, 1);
        state.setPriorGenerationCleanupPending(true);
        state.requestBatch(requestId);

        Assertions.assertEquals(requestId, state.outstandingRequest().orElseThrow());
        Assertions.assertFalse(state.isReadable());
    }

    @Test
    void atMostOneRequestMayBeOutstandingPerGeneration() {
        var state = new PartitionSourceState(GENERATION);
        state.requestBatch(new PartitionBatchRequestId(GENERATION, 1));

        Assertions.assertThrows(
            IllegalStateException.class,
            () -> state.requestBatch(new PartitionBatchRequestId(GENERATION, 2))
        );
    }

    @Test
    void aRequestForAnotherGenerationIsRejected() {
        var state = new PartitionSourceState(GENERATION);
        var otherGeneration =
            new PartitionGenerationId(GENERATION.topicPartition(), GENERATION.localSequence() + 1);

        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> state.requestBatch(new PartitionBatchRequestId(otherGeneration, 1))
        );
    }

    /** An empty poll completes no request, so the request must survive for the next poll to satisfy. */
    @Test
    void bootstrapIsDeliveredBeforeTheOverlappingExplicitRequest() {
        var state = new PartitionSourceState(GENERATION);
        var requestId = new PartitionBatchRequestId(GENERATION, 1);
        state.requestBatch(requestId);

        Assertions.assertEquals(
            new PartitionBatchRequestId(GENERATION, 0),
            state.completeNextBatchEntitlement()
        );
        Assertions.assertFalse(state.isAssignmentBootstrapPending());
        Assertions.assertEquals(
            requestId,
            state.outstandingRequest().orElseThrow(),
            "bootstrap delivery must not consume the overlapping explicit request"
        );
        Assertions.assertTrue(state.isReadable());

        Assertions.assertEquals(requestId, state.completeNextBatchEntitlement());
        Assertions.assertTrue(state.outstandingRequest().isEmpty());
        Assertions.assertFalse(state.isReadable(), "both delivered entitlements leave nothing to read for");

        Assertions.assertThrows(IllegalStateException.class, state::completeNextBatchEntitlement);
    }
}
