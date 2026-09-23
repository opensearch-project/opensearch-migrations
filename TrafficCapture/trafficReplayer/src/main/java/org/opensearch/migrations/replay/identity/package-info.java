/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/**
 * The replayer's process-local identities. There are <strong>exactly eight</strong>, defined by
 * {@code docs/captureAndReplay/replayerKafkaSourceAndIntakeLowLevelDesign.md} section 2 and
 * {@code docs/captureAndReplay/replayerLowLevelDesign.md} section 1.
 *
 * <p>One record per file, deliberately. The prior implementation collected identities as nested records
 * inside a single holder class and accumulated twelve, of which only one matched the design; a ninth
 * identity was a line in a long file rather than a visible event. Here, adding one is a new file, and
 * counting them is listing this directory.</p>
 *
 * <p>The eight, in the order the design introduces them:</p>
 * <ol>
 *   <li>{@link org.opensearch.migrations.replay.identity.PartitionGenerationId}</li>
 *   <li>{@link org.opensearch.migrations.replay.identity.KafkaRecordId}</li>
 *   <li>{@link org.opensearch.migrations.replay.identity.WriterPartitionId}</li>
 *   <li>{@link org.opensearch.migrations.replay.identity.CapturedConnectionId}</li>
 *   <li>{@link org.opensearch.migrations.replay.identity.ConnectionProcessingId}</li>
 *   <li>{@link org.opensearch.migrations.replay.identity.ReplayRequestId}</li>
 *   <li>{@link org.opensearch.migrations.replay.identity.PartitionBatchRequestId}</li>
 *   <li>{@link org.opensearch.migrations.replay.identity.CancellationDeadline}</li>
 * </ol>
 *
 * <p><strong>None of these is added to the capture protobuf.</strong> They are process-local and are never
 * serialized. Two of them carry a {@code localSequence} allocated by this process, which has no meaning to
 * any other process and in particular is not Kafka's group generation.</p>
 *
 * <p>Every record validates its components on construction. A null component or a negative sequence is an
 * invariant violation rather than a recoverable value, per {@code replayerLowLevelDesign.md} section 4, and
 * identities are constructed on paths where failing immediately is far cheaper than discovering a
 * default-valued identity later. The prior implementation's {@code getSourceGeneration()} returning a
 * silent {@code 0} for every non-Kafka key is the failure this guards against: two distinct connection
 * lifetimes collided in a cache because an identity component had a plausible default.</p>
 */
package org.opensearch.migrations.replay.identity;
