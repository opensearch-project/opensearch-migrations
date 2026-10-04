/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.kafka;

/**
 * Indicates that a Kafka value cannot be interpreted as exactly one recognized capture-protocol envelope.
 *
 * <p>Malformed, empty, or unknown payloads cannot be skipped safely because later offsets may depend on the
 * missing connection and request state. This exception preserves that distinction from ordinary transport
 * failures so replay can stop record admission, keep the offending offset uncommitted, drain already-admitted
 * side effects for a bounded interval, and terminate diagnostically.</p>
 */
public class CaptureRecordProtocolViolationException extends IllegalArgumentException {
    public CaptureRecordProtocolViolationException(String message) {
        super(message);
    }

    public CaptureRecordProtocolViolationException(String message, Throwable cause) {
        super(message, cause);
    }
}
