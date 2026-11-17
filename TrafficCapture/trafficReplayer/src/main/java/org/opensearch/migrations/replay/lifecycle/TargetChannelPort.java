/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.lifecycle;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionStage;

import org.opensearch.migrations.replay.identity.ConnectionProcessingId;
import org.opensearch.migrations.replay.identity.PartitionGenerationId;
import org.opensearch.migrations.replay.identity.ReplayRequestId;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.TargetAttemptOutcome;
import org.opensearch.migrations.replay.tracing.IReplayContexts;

import lombok.NonNull;

/**
 * Defines the transport boundary for target attempts on one replayed connection.
 *
 * <p>Starting an attempt encompasses channel reuse or reconnection, request signing at send time, captured
 * packet pacing, request writes, and aggregation of the corresponding target response. Write milestones
 * expose when the first and final packets enter transport so connection ordering can advance at the same
 * boundaries as the original protocol stream.</p>
 *
 * <p>Expected transport failures and missing responses complete as typed {@link TargetAttemptOutcome}
 * values, after any channel teardown needed to make later reuse safe. Aborting an attempt likewise completes
 * only after unsafe framing has been discarded. Exceptional completion is reserved for failures that make
 * the transport state machine itself unreliable.</p>
 */
public interface TargetChannelPort<P, R> {
    record AttemptInput<P>(
        int attemptNumber,
        @NonNull P preparedRequest,
        @NonNull IReplayContexts.ITargetRequestContext replayContext,
        @NonNull WriteMilestoneListener writeMilestones
    ) {
        public AttemptInput {
            if (attemptNumber <= 0) {
                throw new IllegalArgumentException("attemptNumber must be positive");
            }
        }

        public ReplayRequestId requestId() {
            return replayContext.getRequestId();
        }

        public ConnectionProcessingId connectionProcessingId() {
            return replayContext.getConnectionProcessingId();
        }

        public PartitionGenerationId partitionGenerationId() {
            return replayContext.getConnectionProcessingId().generation();
        }
    }

    interface WriteMilestoneListener {
        void firstTargetWriteSubmitted(int attemptNumber);

        void finalTargetWriteSubmitted(int attemptNumber);
    }

    interface Attempt<R> {
        CompletionStage<TargetAttemptOutcome<R>> outcome();

        /**
         * Cancels the attempt and closes any channel whose framing may be unsafe.
         *
         * <p>The returned stage completes only after asynchronous channel teardown is complete.
         * Until then the caller must retain the attempt permit and must not begin a replacement
         * attempt.</p>
         */
        CompletionStage<Void> abort(CancellationException cause);
    }

    Attempt<R> startAttempt(AttemptInput<P> input);

    CompletionStage<Void> close(ConnectionProcessingId connectionProcessingId);
}
