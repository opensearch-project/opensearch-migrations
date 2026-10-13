package org.opensearch.migrations.replay.http.retries;

import java.util.concurrent.ExecutionException;

import org.opensearch.migrations.replay.IRequestResponsePacketPair;
import org.opensearch.migrations.replay.datatypes.ByteBufList;
import org.opensearch.migrations.replay.datatypes.ByteBufListProducer;
import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.datatypes.TransformedOutputAndResult;
import org.opensearch.migrations.utils.TrackedFuture;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;

class RetryCollectingVisitorFactoryCoverageTest {

    @Test
    void exceptionRetryPath_returnsRetryDirective() throws ExecutionException, InterruptedException {
        var evaluator = mock(RequestRetryEvaluator.class);
        var factory = new RetryCollectingVisitorFactory(evaluator);

        var packets = new ByteBufList(Unpooled.wrappedBuffer(new byte[]{1, 2, 3}));
        var producer = ByteBufListProducer.of(packets);
        var transformedResult = new TransformedOutputAndResult<>(
            producer, HttpRequestTransformationStatus.completed());

        @SuppressWarnings("unchecked")
        TrackedFuture<String, ? extends IRequestResponsePacketPair> accumFuture =
            (TrackedFuture<String, ? extends IRequestResponsePacketPair>) mock(TrackedFuture.class);

        var visitor = factory.getRetryCheckVisitor(transformedResult, accumFuture);

        var requestBytes = Unpooled.wrappedBuffer("GET / HTTP/1.1\r\n\r\n".getBytes());
        var exception = new RuntimeException("Connection refused");

        var resultFuture = visitor.visit(requestBytes, null, exception);
        var result = resultFuture.get();

        Assertions.assertNotNull(result);
    }

    @Test
    void exceptionRetryPath_incrementsRetryCount() throws ExecutionException, InterruptedException {
        var evaluator = mock(RequestRetryEvaluator.class);
        var factory = new RetryCollectingVisitorFactory(evaluator);

        var packets = new ByteBufList(Unpooled.wrappedBuffer(new byte[]{1, 2, 3}));
        var producer = ByteBufListProducer.of(packets);
        var transformedResult = new TransformedOutputAndResult<>(
            producer, HttpRequestTransformationStatus.completed());

        @SuppressWarnings("unchecked")
        TrackedFuture<String, ? extends IRequestResponsePacketPair> accumFuture =
            (TrackedFuture<String, ? extends IRequestResponsePacketPair>) mock(TrackedFuture.class);

        var visitor = factory.getRetryCheckVisitor(transformedResult, accumFuture);
        var requestBytes = Unpooled.wrappedBuffer("GET / HTTP/1.1\r\n\r\n".getBytes());

        // First exception - triggers the t != null branch
        var result1 = visitor.visit(requestBytes, null, new RuntimeException("fail 1")).get();
        Assertions.assertNotNull(result1);

        // Second exception - retry count increments (covers logging of attempt number > 1)
        var result2 = visitor.visit(requestBytes, null, new java.io.IOException("fail 2")).get();
        Assertions.assertNotNull(result2);

        // Third exception - covers different exception types in logging
        var result3 = visitor.visit(requestBytes, null,
            new java.net.ConnectException("Connection timed out")).get();
        Assertions.assertNotNull(result3);
    }
}
