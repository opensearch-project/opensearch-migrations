/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.kafkasource;

import org.opensearch.migrations.trafficcapture.protos.CaptureRecord;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.NonNull;

/**
 * Represents one record exactly as returned by a Kafka poll, before local ownership is attached.
 *
 * <p>The value preserves offset, broker {@code LogAppendTime}, serialized size, and the encoded capture
 * envelope because those Kafka-level facts cannot be reconstructed later. It deliberately carries no topic,
 * partition, or generation; the surrounding poll result supplies the partition and the source state machine
 * stamps the generation that is current when the batch is accepted.</p>
 *
 * <p>This separation keeps the Kafka adapter free of replay ownership state and prevents two mutable copies
 * of the partition-generation mapping. The resulting {@link ApplicationKafkaRecord} is therefore the first
 * form that combines broker data with process-local identity.</p>
 */
public record PolledKafkaRecord(
    long offset,
    long logAppendTimeMillis,
    int serializedSizeBytes,
    @NonNull ByteString encodedEnvelope
) {
    public PolledKafkaRecord(
        long offset,
        long logAppendTimeMillis,
        int serializedSizeBytes,
        @NonNull CaptureRecord envelope
    ) {
        this(
            offset,
            logAppendTimeMillis,
            serializedSizeBytes,
            envelope.toByteString()
        );
    }

    public PolledKafkaRecord {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative: " + offset);
        }
        if (serializedSizeBytes < 0) {
            throw new IllegalArgumentException("serializedSizeBytes must not be negative: " + serializedSizeBytes);
        }
    }

    public CaptureRecord decodeEnvelope() throws InvalidProtocolBufferException {
        return CaptureRecord.parseFrom(encodedEnvelope);
    }

    public CaptureRecord envelope() {
        try {
            return decodeEnvelope();
        } catch (InvalidProtocolBufferException notAnEnvelope) {
            throw new IllegalStateException("Kafka value is not a CaptureRecord envelope", notAnEnvelope);
        }
    }
}
