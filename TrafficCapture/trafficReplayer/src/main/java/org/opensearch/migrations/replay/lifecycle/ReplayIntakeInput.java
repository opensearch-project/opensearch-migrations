/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.lifecycle;

/**
 * A named immutable input whose state transition is applied only by the replay-intake owner.
 */
public sealed interface ReplayIntakeInput permits
    ReplayIntakeOwner.Input,
    AsyncPermitPool.Input,
    ReplayProgressController.Input,
    RecordWorkTracker.Input,
    RequestLifecycleInput {}
