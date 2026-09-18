/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 *
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.migrations.utils.async;

import java.util.concurrent.CompletionStage;

/**
 * Accepts one typed message and produces the value required by the preceding asynchronous layer.
 *
 * <p>An implementation that owns mutable state submits the message to that owner's existing Netty
 * event loop, executor, or owner input queue. It must not mutate owner-confined state on the
 * caller's thread.
 */
@FunctionalInterface
public interface AsyncReceiver<M, C, R> {
    CompletionStage<R> receive(M message, C context);
}
