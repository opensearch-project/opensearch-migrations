package org.opensearch.migrations.replay.sink;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;

/**
 * Writes tuples as JSON to a Kafka topic for real-time monitoring.
 *
 * <p>Each tuple is one JSON object per Kafka message, using the connectionId as
 * the record key so all tuples for a connection land on the same partition.
 * The producer is shared across sink instances and is NOT closed in {@link #close()}
 * — the caller owns producer lifecycle.</p>
 *
 * <p>Instances are one-per-Netty-event-loop-thread, so serialization and the
 * {@code producer.send()} call (which can block up to {@code max.block.ms} waiting on
 * metadata or buffer space) are offloaded to a dedicated worker thread rather than run
 * directly in {@link #accept}.</p>
 */
@Slf4j
public class KafkaTupleSink implements TupleSink {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Producer<String, byte[]> producer;
    private final String topic;
    private final ExecutorService executor;

    public KafkaTupleSink(Producer<String, byte[]> producer, String topic) {
        this.producer = producer;
        this.topic = topic;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            var thread = new Thread(r, "kafka-tuple-sink-worker");
            thread.setDaemon(false);
            return thread;
        });
    }

    @Override
    public void accept(Map<String, Object> tupleMap, CompletableFuture<Void> future) {
        executor.execute(() -> {
            final byte[] json;
            try {
                json = mapper.writeValueAsBytes(tupleMap);
            } catch (Exception e) {
                future.completeExceptionally(e);
                return;
            }
            var key = (String) tupleMap.get("connectionId");
            try {
                producer.send(new ProducerRecord<>(topic, key, json), (metadata, exception) -> {
                    if (exception != null) {
                        log.atWarn().setCause(exception).setMessage("Failed to send tuple to Kafka topic {}").addArgument(topic).log();
                        future.completeExceptionally(exception);
                    } else {
                        future.complete(null);
                    }
                });
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
    }

    @Override
    public void flush() {
        producer.flush();
    }

    @Override
    public void close() {
        // Flush only — caller owns the producer and closes it after all sinks are done.
        executor.shutdown();
        try {
            executor.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        producer.flush();
    }
}
