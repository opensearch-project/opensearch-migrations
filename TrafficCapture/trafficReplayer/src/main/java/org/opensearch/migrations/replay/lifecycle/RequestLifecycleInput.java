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

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.PartitionGenerationId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ReplayRequestId;

import lombok.NonNull;

*/
// REBUILD-LIMBO-END(G11)
/**
 * Immutable request-lifecycle results returned from a target-connection owner to replay intake.
 */
// REBUILD-LIMBO-START(G11)
/*
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

*/
// REBUILD-LIMBO-END(G11)