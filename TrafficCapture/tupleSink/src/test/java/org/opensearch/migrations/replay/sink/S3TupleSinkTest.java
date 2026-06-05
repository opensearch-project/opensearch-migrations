package org.opensearch.migrations.replay.sink;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class S3TupleSinkTest {

    private Map<String, Object> makeTuple(String id) {
        var map = new LinkedHashMap<String, Object>();
        map.put("connectionId", id);
        map.put("numRequests", 1);
        return map;
    }

    @Test
    void flushWithoutPendingTuplesDoesNotUpload() {
        var s3Client = mock(S3AsyncClient.class);

        try (var sink = makeSink(s3Client, 1)) {
            sink.flush();
        }

        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(AsyncRequestBody.class));
    }

    @Test
    void serializationFailureCompletesOnlyThatTupleFuture() {
        var s3Client = mock(S3AsyncClient.class);
        var recursiveTuple = new LinkedHashMap<String, Object>();
        recursiveTuple.put("self", recursiveTuple);

        try (var sink = makeSink(s3Client, 1)) {
            var future = new CompletableFuture<Void>();
            sink.accept(recursiveTuple, future);

            assertTrue(future.isCompletedExceptionally());
            assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
        }

        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(AsyncRequestBody.class));
    }

    @Test
    void selfScheduledFlushUploadsPendingTupleOnceMaxAgeReached() throws Exception {
        var s3Client = mock(S3AsyncClient.class);
        var upload = new CompletableFuture<PutObjectResponse>();
        var putCallCount = new AtomicInteger();

        when(s3Client.putObject(any(PutObjectRequest.class), any(AsyncRequestBody.class)))
            .thenAnswer(invocation -> {
                putCallCount.incrementAndGet();
                return upload;
            });

        // Short max-age: the sink's own scheduled worker should rotate the trailing batch
        // WITHOUT any further accept() calls or external flush — this is the stall fix.
        try (var sink = makeSink(s3Client, 100, Duration.ofMillis(50))) {
            var future = new CompletableFuture<Void>();
            sink.accept(makeTuple("conn1.0"), future);

            waitForPutCalls(putCallCount, 1);
            assertFalse(future.isDone(), "Tuple future should still wait for the upload result");

            upload.complete(PutObjectResponse.builder().build());
            future.get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void doesNotUploadBeforeMaxAgeWhileQuiet() throws Exception {
        var s3Client = mock(S3AsyncClient.class);

        // Long max-age: a quiet sink must NOT upload a tiny object on its scheduled ticks.
        try (var sink = makeSink(s3Client, 100, Duration.ofMinutes(10))) {
            var future = new CompletableFuture<Void>();
            sink.accept(makeTuple("conn1.0"), future);

            // Give the scheduled worker several opportunities to (incorrectly) flush.
            Thread.sleep(200);

            verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(AsyncRequestBody.class));
            assertFalse(future.isDone(), "Tuple future stays pending until size/count/age rotation");
        }
    }

    @Test
    void closeUploadsPendingTupleAndAwaitsUpload() throws Exception {
        var s3Client = mock(S3AsyncClient.class);
        var upload = new CompletableFuture<PutObjectResponse>();
        var putCallCount = new AtomicInteger();

        when(s3Client.putObject(any(PutObjectRequest.class), any(AsyncRequestBody.class)))
            .thenAnswer(invocation -> {
                putCallCount.incrementAndGet();
                return upload;
            });

        var sink = makeSink(s3Client, 100);
        var future = new CompletableFuture<Void>();
        sink.accept(makeTuple("conn1.0"), future);

        // Complete the upload shortly after close() starts waiting for it
        var closer = new Thread(() -> sink.close());
        closer.start();
        waitForPutCalls(putCallCount, 1);
        upload.complete(PutObjectResponse.builder().build());
        closer.join(5000);
        assertFalse(closer.isAlive(), "close() should return after uploads complete");

        assertTrue(future.isDone(), "Tuple future should be done after close() awaits upload");
        future.get(1, TimeUnit.SECONDS);
    }

    @Test
    void retriesUploadWithSameS3KeyUntilSuccess() throws Exception {
        var s3Client = mock(S3AsyncClient.class);
        var putRequests = new CopyOnWriteArrayList<PutObjectRequest>();
        var putCallCount = new AtomicInteger();
        var successfulRetry = new CompletableFuture<PutObjectResponse>();

        when(s3Client.putObject(any(PutObjectRequest.class), any(AsyncRequestBody.class)))
            .thenAnswer(invocation -> {
                putRequests.add(invocation.getArgument(0));
                var call = putCallCount.incrementAndGet();
                if (call < 3) {
                    return CompletableFuture.failedFuture(new IOException("S3 unavailable"));
                }
                return successfulRetry;
            });

        try (var sink = makeSink(s3Client, 1)) {

            var future = new CompletableFuture<Void>();
            sink.accept(makeTuple("conn1.0"), future);

            waitForPutCalls(putCallCount, 3);
            assertFalse(future.isDone(), "Tuple future should remain pending until an upload succeeds");
            assertEquals(putRequests.get(0).key(), putRequests.get(1).key());
            assertEquals(putRequests.get(1).key(), putRequests.get(2).key());

            successfulRetry.complete(PutObjectResponse.builder().build());
            future.get(1, TimeUnit.SECONDS);
        }
    }

    private void waitForPutCalls(AtomicInteger putCallCount, int expectedCalls) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (putCallCount.get() < expectedCalls && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(expectedCalls, putCallCount.get(), "Unexpected S3 putObject attempt count");
    }

    private S3TupleSink makeSink(S3AsyncClient s3Client, int rotateAfterTuples) {
        return makeSink(s3Client, rotateAfterTuples, Duration.ofMinutes(10));
    }

    private S3TupleSink makeSink(S3AsyncClient s3Client, int rotateAfterTuples, Duration rotateAfterAge) {
        return new S3TupleSink(
            s3Client,
            "bucket",
            "tuples/",
            "replayer-1",
            0,
            1024 * 1024,
            rotateAfterAge,
            rotateAfterTuples,
            Duration.ofMillis(10)
        );
    }
}
