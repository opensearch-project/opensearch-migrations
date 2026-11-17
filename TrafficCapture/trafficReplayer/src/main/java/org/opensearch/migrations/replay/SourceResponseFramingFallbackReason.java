/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay;

/**
 * Bounded reasons that streaming response framing reverted to connection-boundary reconstruction.
 */
public enum SourceResponseFramingFallbackReason {
    MALFORMED_RESPONSE,
    UNSUPPORTED_UPGRADE,
    DECODER_FAILURE
}
