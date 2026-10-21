/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.intake;

import org.opensearch.migrations.replay.identity.PartitionGenerationId;
import org.opensearch.migrations.replay.identity.ReplayRequestId;

import lombok.NonNull;

/**
 * Reports the two request milestones that change source-record retention and intake backpressure.
 *
 * <p>{@link ConnectionRequestFinished} means the target-connection pipeline no longer needs this request as
 * available supply, so intake may admit replacement work. {@link RequestProcessingFinished} is later and
 * stronger: target handling and durable tuple output are complete, so associations from the request to its
 * contributing Kafka records may be removed.</p>
 *
 * <p>Keeping the milestones distinct prevents throughput accounting from being coupled to commit safety.
 * Both carry the generation and request identity so delayed completions can be matched narrowly or ignored
 * after generation cleanup.</p>
 */
public sealed interface RequestLifecycleInput extends ReplayIntakeInput permits
    RequestLifecycleInput.ConnectionRequestFinished,
    RequestLifecycleInput.RequestProcessingFinished {

    @NonNull PartitionGenerationId partitionGenerationId();

    @NonNull ReplayRequestId requestId();

    @Override
    default PartitionGenerationId generation() {
        return partitionGenerationId();
    }

    record ConnectionRequestFinished(
        @NonNull PartitionGenerationId partitionGenerationId,
        @NonNull ReplayRequestId requestId
    ) implements RequestLifecycleInput {}

    record RequestProcessingFinished(
        @NonNull PartitionGenerationId partitionGenerationId,
        @NonNull ReplayRequestId requestId
    ) implements RequestLifecycleInput {}
}
