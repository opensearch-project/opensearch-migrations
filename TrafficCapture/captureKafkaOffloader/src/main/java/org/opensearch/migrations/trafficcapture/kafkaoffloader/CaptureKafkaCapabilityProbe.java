package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.opensearch.migrations.trafficcapture.protos.CaptureCapabilityProbe;
import org.opensearch.migrations.trafficcapture.protos.CaptureRecord;

import lombok.NonNull;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;

/**
 * Qualifies the Kafka traffic topic before capture begins. Metadata discovery alone cannot prove
 * that the producer may write or that the broker will supply the append timestamps required by
 * replay, so this probe publishes a replay-inert record to one partition led by each broker.
 *
 * <p>The probe completes only after every record is acknowledged with a positive broker timestamp.
 * A send failure, missing timestamp, or non-positive timestamp fails the whole qualification,
 * preventing a partially compatible Kafka cluster from being treated as capture-ready.
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
        @NonNull Producer<String, byte[]> producer,
        @NonNull String topic,
        @NonNull String captureActivationId,
        @NonNull Collection<Integer> representativePartitions,
        @NonNull Supplier<String> probeIdSupplier
    ) {
        if (captureActivationId.isBlank()) {
            throw new IllegalArgumentException("captureActivationId must not be blank");
        }
        var partitions = validatedPartitions(representativePartitions);

        var writerNodeId = captureActivationId + ":PROBE";
        var result = new CompletableFuture<Void>();
        var remainingAcknowledgements = new AtomicInteger(partitions.size());
        for (var partition : partitions) {
            publishProbe(
                producer,
                topic,
                writerNodeId,
                partition,
                probeIdSupplier,
                result,
                remainingAcknowledgements
            );
            if (result.isCompletedExceptionally()) {
                break;
            }
        }
        return result;
    }

    private static List<Integer> validatedPartitions(
        Collection<Integer> representativePartitions
    ) {
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
        return partitions;
    }

    private static void publishProbe(
        Producer<String, byte[]> producer,
        String topic,
        String writerNodeId,
        int partition,
        Supplier<String> probeIdSupplier,
        CompletableFuture<Void> result,
        AtomicInteger remainingAcknowledgements
    ) {
        var probeId = Objects.requireNonNull(probeIdSupplier.get());
        if (probeId.isBlank()) {
            throw new IllegalArgumentException("probeId must not be blank");
        }
        var payload = CaptureRecord.newBuilder()
            .setCaptureCapabilityProbe(
                CaptureCapabilityProbe.newBuilder()
                    .setWriterNodeId(writerNodeId)
                    .setProbeId(probeId)
            )
            .build()
            .toByteArray();
        var producerRecord = new ProducerRecord<String, byte[]>(
            topic,
            partition,
            0L,
            writerNodeId + ":" + probeId,
            payload
        );
        try {
            producer.send(producerRecord, (metadata, failure) -> {
                if (failure != null) {
                    result.completeExceptionally(failure);
                } else if (metadata == null || !metadata.hasTimestamp() || metadata.timestamp() <= 0) {
                    result.completeExceptionally(new IllegalStateException(
                        "Kafka capability probe did not receive a positive broker-assigned timestamp for "
                            + topic
                            + "/"
                            + partition
                            + "; the traffic topic must use message.timestamp.type=LogAppendTime"
                    ));
                } else if (remainingAcknowledgements.decrementAndGet() == 0) {
                    result.complete(null);
                }
            });
        } catch (Throwable t) {
            result.completeExceptionally(t);
        }
    }
}
