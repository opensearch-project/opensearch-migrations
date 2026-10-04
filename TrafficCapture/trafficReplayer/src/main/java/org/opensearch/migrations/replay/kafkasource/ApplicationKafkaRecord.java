/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.kafkasource;

import org.opensearch.migrations.replay.identity.KafkaRecordId;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.trafficcapture.protos.CaptureRecord;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.NonNull;

/**
 * Carries one Kafka record after it has been stamped with its process-local ownership identity.
 *
 * <p>The record combines the generation-qualified offset with broker {@code LogAppendTime}, serialized size,
 * encoded capture envelope, and optional tracing scope. Keeping broker time alongside the payload lets
 * intake validate timestamp movement, resolve retry windows, and expire writer state using evidence from
 * the partition log rather than the replayer's wall clock.</p>
 *
 * <p>The envelope remains encoded until intake applies the record. Decoding and the exhaustive protocol
 * payload switch therefore occur at the same serialized state boundary that owns record associations and
 * protocol-violation handling, rather than being partially interpreted by the Kafka adapter.</p>
 */
public record ApplicationKafkaRecord(
    @NonNull KafkaRecordId recordId,
    long logAppendTimeMillis,
    int serializedSizeBytes,
    @NonNull ByteString encodedEnvelope,
    IReplayContexts.IKafkaRecordContext replayContext
) {
    public ApplicationKafkaRecord(
        KafkaRecordId recordId,
        long logAppendTimeMillis,
        int serializedSizeBytes,
        @NonNull CaptureRecord envelope
    ) {
        this(
            recordId,
            logAppendTimeMillis,
            serializedSizeBytes,
            envelope.toByteString(),
            null
        );
    }

    public ApplicationKafkaRecord(
        KafkaRecordId recordId,
        long logAppendTimeMillis,
        int serializedSizeBytes,
        @NonNull CaptureRecord envelope,
        IReplayContexts.IKafkaRecordContext replayContext
    ) {
        this(
            recordId,
            logAppendTimeMillis,
            serializedSizeBytes,
            envelope.toByteString(),
            replayContext
        );
    }

    public ApplicationKafkaRecord {
        if (serializedSizeBytes < 0) {
            throw new IllegalArgumentException("serializedSizeBytes must not be negative: " + serializedSizeBytes);
        }
        // No bound on logAppendTimeMillis. A broker timestamp is whatever the broker recorded; validating it
        // here would duplicate -- and could silently pre-empt -- the backward-skew check that kafkaLLD 10.1
        // makes process-fatal, which must be evaluated against the partition's greatest observed value rather
        // than against any constant.
    }

    public CaptureRecord decodeEnvelope() throws InvalidProtocolBufferException {
        return CaptureRecord.parseFrom(encodedEnvelope);
    }

    /**
     * Convenience for code that runs only after replay intake has validated the encoded value.
     */
    public CaptureRecord envelope() {
        try {
            return decodeEnvelope();
        } catch (InvalidProtocolBufferException notAnEnvelope) {
            throw new IllegalStateException(recordId + " is not a CaptureRecord envelope", notAnEnvelope);
        }
    }

    @Override
    public String toString() {
        try {
            return recordId + "{" + decodeEnvelope().getPayloadCase()
                + " logAppendTime=" + logAppendTimeMillis + "}";
        } catch (InvalidProtocolBufferException notAnEnvelope) {
            return recordId + "{UNPARSEABLE logAppendTime=" + logAppendTimeMillis + "}";
        }
    }
}
