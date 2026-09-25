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

import lombok.NonNull;

/**
 * The only boundary allowed to mutate a target channel for a connection owner.
 *
 * <p>An attempt owns channel reuse/reconnect, captured packet pacing, immediate-before-attempt
 * signing, request writes, and response aggregation. Its completion is always a typed
 * {@link TargetAttemptOutcome}. Implementations create no-response values at this boundary after
 * any channel teardown required by that outcome has completed. Unexpected failures complete
 * exceptionally and are process-fatal to the owner.</p>
 */
public interface TargetChannelPort<P, R> {
    record AttemptInput<P>(
        @NonNull PartitionGenerationId partitionGenerationId,
        @NonNull ConnectionProcessingId connectionProcessingId,
        @NonNull ReplayRequestId requestId,
        int attemptNumber,
        @NonNull P preparedRequest,
        @NonNull WriteMilestoneListener writeMilestones
    ) {
        public AttemptInput {
            if (attemptNumber <= 0) {
                throw new IllegalArgumentException("attemptNumber must be positive");
            }
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
