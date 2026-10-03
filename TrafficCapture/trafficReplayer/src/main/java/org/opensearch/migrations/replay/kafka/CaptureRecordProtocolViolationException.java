/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.kafka;

/**
 * Raised when a Kafka application record is not exactly one recognized CaptureRecord envelope.
 *
 * <p>S14 installs the bounded process-level protocol-violation drain. Until then this exception
 * deliberately fails the current source operation instead of dropping or trial-decoding the record.
 */
public class CaptureRecordProtocolViolationException extends IllegalArgumentException {
    public CaptureRecordProtocolViolationException(String message) {
        super(message);
    }

    public CaptureRecordProtocolViolationException(String message, Throwable cause) {
        super(message, cause);
    }
}
