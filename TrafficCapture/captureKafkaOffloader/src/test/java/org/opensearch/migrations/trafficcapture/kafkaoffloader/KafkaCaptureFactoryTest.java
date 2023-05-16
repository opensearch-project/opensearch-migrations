package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import io.netty.buffer.Unpooled;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.migrations.trafficcapture.IChannelConnectionCaptureSerializer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class KafkaCaptureFactoryTest {

    @Mock
    private Producer<String, byte[]> mockProducer;

    private String connectionId = "test1234";


    @Test
    public void testLinearOffloadingIsSuccessful() throws IOException {
        KafkaCaptureFactory kafkaCaptureFactory = new KafkaCaptureFactory(mockProducer, 1024*1024);
        IChannelConnectionCaptureSerializer offloader = kafkaCaptureFactory.createOffloader(connectionId);

        List<Callback> recordSentCallbacks = new ArrayList<>(3);
        when(mockProducer.send(any(), any())).thenAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            ProducerRecord<String, byte[]> record = (ProducerRecord) args[0];
            Callback recordSentCallback = (Callback) args[1];
            recordSentCallbacks.add(recordSentCallback);
            return null;
        });

        Instant ts = Instant.now();
        byte[] fakeDataBytes = "FakeData".getBytes(StandardCharsets.UTF_8);
        var bb = Unpooled.wrappedBuffer(fakeDataBytes);
        offloader.addReadEvent(ts, bb);
        CompletableFuture cf1 = offloader.flushCommitAndResetStream(false);
        offloader.addReadEvent(ts, bb);
        CompletableFuture cf2 = offloader.flushCommitAndResetStream(false);
        offloader.addReadEvent(ts, bb);
        CompletableFuture cf3 = offloader.flushCommitAndResetStream(false);
        bb.release();

        Assertions.assertEquals(false, cf1.isDone());
        Assertions.assertEquals(false, cf2.isDone());
        Assertions.assertEquals(false, cf3.isDone());
        recordSentCallbacks.get(0).onCompletion(null, null);

        Assertions.assertEquals(true, cf1.isDone());
        Assertions.assertEquals(false, cf2.isDone());
        Assertions.assertEquals(false, cf3.isDone());
        recordSentCallbacks.get(1).onCompletion(null, null);

        Assertions.assertEquals(true, cf1.isDone());
        Assertions.assertEquals(true, cf2.isDone());
        Assertions.assertEquals(false, cf3.isDone());
        recordSentCallbacks.get(2).onCompletion(null, null);

        Assertions.assertEquals(true, cf1.isDone());
        Assertions.assertEquals(true, cf2.isDone());
        Assertions.assertEquals(true, cf3.isDone());

        mockProducer.close();
    }

    @Test
    public void testOngoingFuturesAreAggregated() throws IOException {
        KafkaCaptureFactory kafkaCaptureFactory = new KafkaCaptureFactory(mockProducer, 1024*1024);
        IChannelConnectionCaptureSerializer offloader = kafkaCaptureFactory.createOffloader(connectionId);

        List<Callback> recordSentCallbacks = new ArrayList<>(3);
        when(mockProducer.send(any(), any())).thenAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            ProducerRecord<String, byte[]> record = (ProducerRecord) args[0];
            Callback recordSentCallback = (Callback) args[1];
            recordSentCallbacks.add(recordSentCallback);
            return null;
        });

        Instant ts = Instant.now();
        byte[] fakeDataBytes = "FakeData".getBytes(StandardCharsets.UTF_8);
        var bb = Unpooled.wrappedBuffer(fakeDataBytes);
        offloader.addReadEvent(ts, bb);
        CompletableFuture cf1 = offloader.flushCommitAndResetStream(false);
        offloader.addReadEvent(ts, bb);
        CompletableFuture cf2 = offloader.flushCommitAndResetStream(false);
        offloader.addReadEvent(ts, bb);
        CompletableFuture cf3 = offloader.flushCommitAndResetStream(false);
        bb.release();

        Assertions.assertEquals(false, cf1.isDone());
        Assertions.assertEquals(false, cf2.isDone());
        Assertions.assertEquals(false, cf3.isDone());
        recordSentCallbacks.get(2).onCompletion(null, null);

        // Assert that even though particular final producer record has finished sending, its predecessors are incomplete
        // and thus this wrapper cf is also incomplete
        Assertions.assertEquals(false, cf1.isDone());
        Assertions.assertEquals(false, cf2.isDone());
        Assertions.assertEquals(false, cf3.isDone());
        recordSentCallbacks.get(1).onCompletion(null, null);

        Assertions.assertEquals(false, cf1.isDone());
        Assertions.assertEquals(false, cf2.isDone());
        Assertions.assertEquals(false, cf3.isDone());
        recordSentCallbacks.get(0).onCompletion(null, null);

        Assertions.assertEquals(true, cf1.isDone());
        Assertions.assertEquals(true, cf2.isDone());
        Assertions.assertEquals(true, cf3.isDone());

        mockProducer.close();
    }
}
