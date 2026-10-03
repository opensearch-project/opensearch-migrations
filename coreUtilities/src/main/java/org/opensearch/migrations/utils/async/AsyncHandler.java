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
 * Handles one immutable input and produces one typed asynchronous output.
 *
 * <p>Expected operation outcomes belong in the output type. A synchronous throw or exceptional
 * stage completion is an unexpected process failure when it escapes the owning component's
 * execution adapter.
 */
@FunctionalInterface
public interface AsyncHandler<I, C, O> {
    CompletionStage<O> handle(I input, C context);
}
