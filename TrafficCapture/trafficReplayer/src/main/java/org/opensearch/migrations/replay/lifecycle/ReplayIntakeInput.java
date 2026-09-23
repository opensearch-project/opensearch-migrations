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

*/
// REBUILD-LIMBO-END(G11)
/**
 * A named immutable input whose state transition is applied only by the replay-intake owner.
 */
// REBUILD-LIMBO-START(G11)
/*
public sealed interface ReplayIntakeInput permits
    ReplayIntakeOwner.Input,
    TargetAttemptPermitProvider.Input,
    ReplayProgressController.Input,
    RecordWorkTracker.Input,
    RequestLifecycleInput {}

*/
// REBUILD-LIMBO-END(G11)