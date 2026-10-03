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
 * Immutable request-lifecycle results returned from a target-connection owner to replay intake.
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
