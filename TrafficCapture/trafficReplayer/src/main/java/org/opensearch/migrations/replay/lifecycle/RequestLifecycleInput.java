/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.lifecycle;

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.PartitionGenerationId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ReplayRequestId;

import lombok.NonNull;

/**
 * Immutable request-lifecycle results returned from a target-connection owner to replay intake.
 */
public sealed interface RequestLifecycleInput extends ReplayIntakeInput permits
    RequestLifecycleInput.ConnectionRequestFinished,
    RequestLifecycleInput.RequestProcessingFinished {

    @NonNull PartitionGenerationId partitionGenerationId();

    @NonNull ReplayRequestId requestId();

    record ConnectionRequestFinished(
        @NonNull PartitionGenerationId partitionGenerationId,
        @NonNull ReplayRequestId requestId
    ) implements RequestLifecycleInput {}

    record RequestProcessingFinished(
        @NonNull PartitionGenerationId partitionGenerationId,
        @NonNull ReplayRequestId requestId
    ) implements RequestLifecycleInput {}
}
