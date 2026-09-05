package org.opensearch.migrations.replay.sink;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KafkaTupleSinkTest {

    private static final String TOPIC = "tuple-output";

    private Map<String, Object> makeTuple(String id) {
        var map = new LinkedHashMap<String, Object>();
        map.put("connectionId", id);
        map.put("numRequests", 1);
        return map;
    }

    /** Only needed by tests that reach an actual producer.send() call — MockProducer's no-arg
     * constructor has no serializers and NPEs on send(). */
    private MockProducer<String, byte[]> makeSendingProducer() {
        return new MockProducer<>(true, null, new StringSerializer(), new ByteArraySerializer());
    }

    @Test
    void acceptSendsRecordToKafkaTopicKeyedByConnectionId() throws Exception {
        var producer = makeSendingProducer();

        try (var sink = new KafkaTupleSink(producer, TOPIC)) {
            var future = new CompletableFuture<Void>();
            sink.accept(makeTuple("conn1.0"), future);
            future.get(1, TimeUnit.SECONDS);
        }

        assertEquals(1, producer.history().size());
        var record = producer.history().get(0);
        assertEquals(TOPIC, record.topic());
        assertEquals("conn1.0", record.key());
    }

    @Test
    void serializationFailureCompletesOnlyThatTupleFuture() {
        var producer = new MockProducer<String, byte[]>();
        var recursiveTuple = new LinkedHashMap<String, Object>();
        recursiveTuple.put("self", recursiveTuple);

        try (var sink = new KafkaTupleSink(producer, TOPIC)) {
            var future = new CompletableFuture<Void>();
            sink.accept(recursiveTuple, future);

            assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
            assertTrue(future.isCompletedExceptionally());
        }

        assertEquals(0, producer.history().size());
    }

    @Test
    void acceptAfterCloseFailsFutureInsteadOfThrowing() throws Exception {
        var producer = new MockProducer<String, byte[]>();
        var sink = new KafkaTupleSink(producer, TOPIC);
        sink.close();

        var future = new CompletableFuture<Void>();
        // Must not throw RejectedExecutionException synchronously to the caller — it should be
        // reported through the future, same as S3TupleSink's accept-after-close behavior.
        sink.accept(makeTuple("conn1.0"), future);

        assertTrue(future.isCompletedExceptionally(), "accept() after close() should fail the future");
        var ex = assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
        assertTrue(ex.getCause() instanceof IllegalStateException,
            "Expected IllegalStateException, got: " + ex.getCause());
        assertEquals(0, producer.history().size(), "No record should have been sent after close");
    }

    @Test
    void closeFlushesProducer() {
        var producer = new MockProducer<String, byte[]>();

        var sink = new KafkaTupleSink(producer, TOPIC);
        sink.close();

        assertTrue(producer.flushed(), "close() should flush the producer");
    }
}
