package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.opensearch.migrations.trafficcapture.protos.CaptureCapabilityProbe;
import org.opensearch.migrations.trafficcapture.protos.CaptureRecordTypes;

import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;

/**
 * Publishes the acknowledged, replay-inert records required before a proxy joins its Kafka group.
 */
final class CaptureKafkaCapabilityProbe {
    private CaptureKafkaCapabilityProbe() {}

    static CompletableFuture<Void> publish(
        Producer<String, byte[]> producer,
        String topic,
        String captureActivationId,
        Collection<Integer> representativePartitions
    ) {
        return publish(
            producer,
            topic,
            captureActivationId,
            representativePartitions,
            () -> UUID.randomUUID().toString()
        );
    }

    static CompletableFuture<Void> publish(
        Producer<String, byte[]> producer,
        String topic,
        String captureActivationId,
        Collection<Integer> representativePartitions,
        Supplier<String> probeIdSupplier
    ) {
        Objects.requireNonNull(producer);
        Objects.requireNonNull(topic);
        Objects.requireNonNull(captureActivationId);
        Objects.requireNonNull(representativePartitions);
        Objects.requireNonNull(probeIdSupplier);
        if (captureActivationId.isBlank()) {
            throw new IllegalArgumentException("captureActivationId must not be blank");
        }
        var partitions = List.copyOf(representativePartitions);
        if (partitions.isEmpty()) {
            throw new IllegalArgumentException("At least one representative partition is required");
        }
        if (new HashSet<>(partitions).size() != partitions.size()) {
            throw new IllegalArgumentException("Representative partitions must be unique");
        }
        if (partitions.stream().anyMatch(partition -> partition < 0)) {
            throw new IllegalArgumentException("Representative partitions must not be negative");
        }

        var writerNodeId = captureActivationId + ":PROBE";
        var result = new CompletableFuture<Void>();
        var remainingAcknowledgements = new AtomicInteger(partitions.size());
        for (var partition : partitions) {
            var probeId = Objects.requireNonNull(probeIdSupplier.get());
            if (probeId.isBlank()) {
                throw new IllegalArgumentException("probeId must not be blank");
            }
            var payload = CaptureCapabilityProbe.newBuilder()
                .setWriterNodeId(writerNodeId)
                .setProbeId(probeId)
                .build()
                .toByteArray();
            var record = new ProducerRecord<String, byte[]>(
                topic,
                partition,
                null,
                writerNodeId + ":" + probeId,
                payload,
                new RecordHeaders(List.of(new RecordHeader(
                    CaptureRecordTypes.RECORD_TYPE_HEADER,
                    CaptureRecordTypes.CAPABILITY_PROBE_RECORD_TYPE.getBytes(StandardCharsets.UTF_8)
                )))
            );
            try {
                producer.send(record, (metadata, failure) -> {
                    if (failure != null) {
                        result.completeExceptionally(failure);
                    } else if (remainingAcknowledgements.decrementAndGet() == 0) {
                        result.complete(null);
                    }
                });
            } catch (Throwable t) {
                result.completeExceptionally(t);
                break;
            }
            if (result.isCompletedExceptionally()) {
                break;
            }
        }
        return result;
    }
}
