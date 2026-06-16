package org.opensearch.migrations.reindexer.faileddocumentstream;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.test.StepVerifier;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class S3FailedDocumentStreamSinkTest {

    private record CapturedUpload(String s3Uri, byte[] data) {}

    @Test
    void writesGzippedNdjsonAndIsolatesBySession() throws Exception {
        var captured = new ArrayList<CapturedUpload>();
        S3FailedDocumentStreamSink.S3Uploader testUploader = (uri, data, region) -> captured.add(new CapturedUpload(uri, data));

        var sink = S3FailedDocumentStreamSink.builder()
            .bucket("my-bucket")
            .prefix("rfs-failed-document-stream/")
            .sessionId("sess-A")
            .workerId("worker-1")
            .region("us-east-1")
            .uploader(testUploader)
            .build();

        var mapper = new ObjectMapper();
        ObjectNode req1 = mapper.createObjectNode();
        req1.put("op", "index").put("_id", "doc-1");
        ObjectNode resp1 = mapper.createObjectNode();
        resp1.putObject("index").put("_id", "doc-1").put("status", 400);

        sink.write(FailedDocumentStreamRecord.builder()
            .sessionId("sess-A")
            .workerId("worker-1")
            .targetIndex("movies")
            .documentId("doc-1")
            .failureType("mapper_parsing_exception")
            .failureClass(FailureClass.NON_RETRYABLE)
            .timestamp(Instant.parse("2026-05-14T12:00:00Z").toString())
            .requestItem(req1)
            .responseItem(resp1)
            .build()).subscribe();

        sink.flush().block();
        sink.close();

        assertThat(captured, hasSize(1));
        assertThat(captured.get(0).s3Uri(),
            startsWith("s3://my-bucket/rfs-failed-document-stream/session=sess-A/index=movies/worker=worker-1/failed-document-stream-"));

        var decoded = decode(captured.get(0).data());
        assertThat(decoded, containsString("\"documentId\":\"doc-1\""));
        assertThat(decoded, containsString("\"failureClass\":\"NON_RETRYABLE\""));
        assertThat(decoded, containsString("\"failureType\":\"mapper_parsing_exception\""));

        var lines = decoded.strip().split("\n");
        assertThat(lines.length, equalTo(1));
        JsonNode parsed = mapper.readTree(lines[0]);
        assertThat(parsed.path("targetIndex").asText(), equalTo("movies"));
        assertThat(parsed.path("requestItem").path("op").asText(), equalTo("index"));
        assertThat(parsed.path("responseItem").path("index").path("status").asInt(), equalTo(400));
        assertThat(sink.getLocation(), equalTo("s3://my-bucket/rfs-failed-document-stream/session=sess-A/"));
    }

    @Test
    void singleFlushSplitsRecordsIntoOnePerTargetIndex() {
        // Records for different indices buffered before one flush must land in separate
        // S3 objects, each keyed by its own index= segment.
        var captured = new ArrayList<CapturedUpload>();
        var sink = S3FailedDocumentStreamSink.builder()
            .bucket("b").prefix("rfs-failed-document-stream/").sessionId("s").workerId("w").region("r")
            .uploader((uri, data, region) -> captured.add(new CapturedUpload(uri, data)))
            .build();

        sink.write(buildRecordForIndex("movies", "m-1")).subscribe();
        sink.write(buildRecordForIndex("books", "b-1")).subscribe();
        sink.write(buildRecordForIndex("movies", "m-2")).subscribe();
        sink.flush().block();

        assertThat(captured, hasSize(2));
        var uris = captured.stream().map(CapturedUpload::s3Uri).toList();
        assertThat(uris, hasItem(containsString("/session=s/index=movies/worker=w/failed-document-stream-")));
        assertThat(uris, hasItem(containsString("/session=s/index=books/worker=w/failed-document-stream-")));
    }

    @Test
    void blankTargetIndexFallsBackToUnknownIndex() {
        // Covers the isBlank() branch of sanitizeIndex (the null test short-circuits it).
        var captured = new ArrayList<CapturedUpload>();
        var sink = S3FailedDocumentStreamSink.builder()
            .bucket("b").prefix("rfs-failed-document-stream/").sessionId("s").workerId("w").region("r")
            .uploader((uri, data, region) -> captured.add(new CapturedUpload(uri, data)))
            .build();

        sink.write(buildRecordForIndex("   ", "d-1")).subscribe();
        sink.flush().block();

        assertThat(captured, hasSize(1));
        assertThat(captured.get(0).s3Uri(), containsString("/session=s/index=unknown-index/worker=w/failed-document-stream-"));
    }

    @Test
    void flushUploadFailureMidLoopPropagatesAfterEarlierIndexUploaded() {
        // Two indices are buffered; the first uploads fine, the second throws.
        // Verifies the per-index flush loop surfaces the later failure as a Mono error
        // rather than swallowing it after an earlier successful upload.
        var captured = new ArrayList<CapturedUpload>();
        var sink = S3FailedDocumentStreamSink.builder()
            .bucket("b").prefix("rfs-failed-document-stream/").sessionId("s").workerId("w").region("r")
            .uploader((uri, data, region) -> {
                if (uri.contains("index=books/")) {
                    throw new RuntimeException("second upload failed");
                }
                captured.add(new CapturedUpload(uri, data));
            })
            .build();

        // LinkedHashMap preserves insertion order, so "movies" uploads before "books".
        sink.write(buildRecordForIndex("movies", "m-1")).subscribe();
        sink.write(buildRecordForIndex("books", "b-1")).subscribe();

        StepVerifier.create(sink.flush())
            .expectErrorMatches(t -> t instanceof RuntimeException
                && t.getMessage().contains("second upload failed"))
            .verify();

        // The first index's object was uploaded before the failure.
        assertThat(captured, hasSize(1));
        assertThat(captured.get(0).s3Uri(), containsString("index=movies/"));
    }

    @Test
    void recordWithoutTargetIndexFallsBackToUnknownIndex() throws Exception {
        var captured = new ArrayList<CapturedUpload>();
        var sink = S3FailedDocumentStreamSink.builder()
            .bucket("b").prefix("rfs-failed-document-stream/").sessionId("s").workerId("w").region("r")
            .uploader((uri, data, region) -> captured.add(new CapturedUpload(uri, data)))
            .build();

        var mapper = new ObjectMapper();
        sink.write(FailedDocumentStreamRecord.builder()
            .sessionId("s").workerId("w").targetIndex(null).documentId("d-1")
            .failureType("err").failureClass(FailureClass.RETRYABLE_EXHAUSTED)
            .timestamp(Instant.parse("2026-05-14T12:00:00Z").toString())
            .requestItem(mapper.createObjectNode())
            .responseItem(mapper.createObjectNode())
            .build()).subscribe();
        sink.flush().block();

        assertThat(captured, hasSize(1));
        assertThat(captured.get(0).s3Uri(), containsString("/session=s/index=unknown-index/worker=w/failed-document-stream-"));
    }

    @Test
    void rotatesMidShardWhenBufferExceedsThresholdAndFlushesRemainder() throws Exception {
        var captured = new ArrayList<CapturedUpload>();
        var recBytes = new ObjectMapper().writeValueAsBytes(buildRecordForIndex("idx", "d1")).length + 1;
        // Threshold fits one record but is crossed by the second, forcing a mid-shard rotation.
        var sink = S3FailedDocumentStreamSink.builder()
            .bucket("b").prefix("rfs-failed-document-stream/").sessionId("s").workerId("w").region("r")
            .maxBufferBytes(recBytes + 1)
            .uploader((uri, data, region) -> captured.add(new CapturedUpload(uri, data)))
            .build();

        sink.write(buildRecordForIndex("idx", "d1")).subscribe();
        sink.write(buildRecordForIndex("idx", "d2")).subscribe();
        // The second write crosses the cap and rotates — one object is uploaded before any flush.
        assertThat(captured, hasSize(1));

        sink.write(buildRecordForIndex("idx", "d3")).subscribe();
        sink.flush().block();
        // flush() uploads the remaining partial buffer (the third record).
        assertThat(captured, hasSize(2));

        assertThat(countLines(captured.get(0).data()), equalTo(2));
        assertThat(countLines(captured.get(1).data()), equalTo(1));
        // Distinct sequence numbers keep the two objects from colliding.
        assertThat(captured.get(0).s3Uri(), not(equalTo(captured.get(1).s3Uri())));
    }

    @Test
    void rotationUploadFailureBlocksGatingFlushThenClears() {
        // A failed rotation upload must make the gating flush() fail too, so the work item
        // is not marked complete and a successor reprocesses. The error is surfaced once.
        var sink = S3FailedDocumentStreamSink.builder()
            .bucket("b").prefix("rfs-failed-document-stream/").sessionId("s").workerId("w").region("r")
            .maxBufferBytes(1)   // rotate on every write
            .uploader((uri, data, region) -> {
                throw new RuntimeException("rotate upload failed");
            })
            .build();

        StepVerifier.create(sink.write(buildRecord("d1")))
            .expectErrorMatches(t -> t instanceof RuntimeException
                && t.getMessage().contains("rotate upload failed"))
            .verify();

        // Gating flush re-surfaces the retained rotation error.
        StepVerifier.create(sink.flush())
            .expectErrorMatches(t -> t instanceof RuntimeException
                && t.getMessage().contains("rotate upload failed"))
            .verify();

        // Once surfaced, it's cleared so a later shard reusing this sink isn't penalized.
        StepVerifier.create(sink.flush()).verifyComplete();
    }

    @Test
    void concurrentWritesAndFlushesDoNotCorruptOrLoseRecords() throws Exception {
        // batchConcurrency defaults to 10, so write() runs concurrently with the per-batch
        // flush(). Every record must survive (gzip streams uncorrupted) and the total line
        // count across all uploaded objects must equal the number of writes — no loss, no dup.
        var captured = new CopyOnWriteArrayList<CapturedUpload>();
        var sink = S3FailedDocumentStreamSink.builder()
            .bucket("b").prefix("rfs-failed-document-stream/").sessionId("s").workerId("w").region("r")
            .maxBufferBytes(2048)   // small cap so rotations also race with writes/flushes
            .uploader((uri, data, region) -> captured.add(new CapturedUpload(uri, data)))
            .build();

        int threads = 8;
        int perThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        sink.write(buildRecordForIndex("idx", "d-" + tid + "-" + i)).block();
                        if (i % 20 == 0) {
                            sink.flush().block();   // flushes concurrent with other threads' writes
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS), equalTo(true));
        pool.shutdown();
        sink.close();   // flush any remainder

        int totalLines = 0;
        for (var c : captured) {
            totalLines += countLines(c.data());   // decode throws if any gzip object is corrupt
        }
        assertThat(totalLines, equalTo(threads * perThread));
    }

    @Test
    void locationReflectsSessionPrefixWithoutTrailingSlashOnInput() {
        var sink = S3FailedDocumentStreamSink.builder()
            .bucket("b")
            .prefix("foo/bar")
            .sessionId("sess-XYZ")
            .workerId("w")
            .region("us-east-1")
            .uploader((uri, data, region) -> {})
            .build();
        assertThat(sink.getLocation(), equalTo("s3://b/foo/bar/session=sess-XYZ/"));
        assertThat(sink, notNullValue());
    }

    @Test
    void emptyPrefixIsAllowedAndProducesRootLayout() {
        // Operators sometimes don't set a prefix at all — we shouldn't bake in a
        // mandatory "rfs-failed-document-stream/" segment.
        var sink = S3FailedDocumentStreamSink.builder()
            .bucket("b").prefix("").sessionId("s").workerId("w").region("r")
            .uploader((uri, data, region) -> {}).build();
        assertThat(sink.getLocation(), equalTo("s3://b/session=s/"));
    }

    @Test
    void nullPrefixIsTreatedAsEmpty() {
        var sink = S3FailedDocumentStreamSink.builder()
            .bucket("b").prefix(null).sessionId("s").workerId("w").region("r")
            .uploader((uri, data, region) -> {}).build();
        assertThat(sink.getLocation(), equalTo("s3://b/session=s/"));
    }

    @Test
    void flushWithNoBufferedRecordsIsNoOp() {
        // The flush contract is "make whatever's buffered durable" — when nothing
        // is buffered, there's nothing to upload and the returned Mono should
        // complete without invoking the uploader.
        var captured = new ArrayList<CapturedUpload>();
        var sink = S3FailedDocumentStreamSink.builder()
            .bucket("b").prefix("p/").sessionId("s").workerId("w").region("r")
            .uploader((uri, data, region) -> captured.add(new CapturedUpload(uri, data)))
            .build();

        sink.flush().block();

        assertThat(captured, hasSize(0));
    }

    @Test
    void writeAfterCloseReturnsError() {
        var sink = S3FailedDocumentStreamSink.builder()
            .bucket("b").prefix("p/").sessionId("s").workerId("w").region("r")
            .uploader((uri, data, region) -> {}).build();
        sink.close();

        // After close, future writes must surface IllegalStateException through
        // the Mono so the caller's error handler can react.
        StepVerifier.create(sink.write(buildRecord("doc-1")))
            .expectError(IllegalStateException.class)
            .verify();
    }

    @Test
    void closeIsIdempotent() {
        var captured = new ArrayList<CapturedUpload>();
        var sink = S3FailedDocumentStreamSink.builder()
            .bucket("b").prefix("p/").sessionId("s").workerId("w").region("r")
            .uploader((uri, data, region) -> captured.add(new CapturedUpload(uri, data)))
            .build();
        sink.write(buildRecord("doc-1")).subscribe();
        sink.close();
        sink.close();   // second close must be a safe no-op

        // Only one upload, even though close ran twice.
        assertThat(captured, hasSize(1));
    }

    @Test
    void closeWithoutAnyWritesIsSafe() {
        // Constructor finishes with gzipOut == null; closing here must not NPE.
        var sink = S3FailedDocumentStreamSink.builder()
            .bucket("b").prefix("p/").sessionId("s").workerId("w").region("r")
            .uploader((uri, data, region) -> {}).build();
        sink.close();   // no exception
        assertThat(sink.getLocation(), equalTo("s3://b/p/session=s/"));
    }

    @Test
    void closeAfterWritesUploadsBufferedData() {
        var captured = new ArrayList<CapturedUpload>();
        var sink = S3FailedDocumentStreamSink.builder()
            .bucket("b").prefix("p/").sessionId("s").workerId("w").region("r")
            .uploader((uri, data, region) -> captured.add(new CapturedUpload(uri, data)))
            .build();
        sink.write(buildRecord("doc-1")).subscribe();
        sink.close();
        assertThat(captured, hasSize(1));
    }

    @Test
    void uploaderIoExceptionPropagatesAsMonoError() {
        var sink = S3FailedDocumentStreamSink.builder()
            .bucket("b").prefix("p/").sessionId("s").workerId("w").region("r")
            .uploader((uri, data, region) -> {
                throw new IOException("S3 unreachable");
            }).build();
        sink.write(buildRecord("doc-1")).subscribe();

        StepVerifier.create(sink.flush())
            .expectErrorMatches(t -> t instanceof IOException
                && t.getMessage().contains("S3 unreachable"))
            .verify();
    }

    @Test
    void uploaderRuntimeExceptionPropagatesAsMonoError() {
        var sink = S3FailedDocumentStreamSink.builder()
            .bucket("b").prefix("p/").sessionId("s").workerId("w").region("r")
            .uploader((uri, data, region) -> {
                throw new RuntimeException("auth failure");
            }).build();
        sink.write(buildRecord("doc-1")).subscribe();

        StepVerifier.create(sink.flush())
            .expectErrorMatches(t -> t instanceof RuntimeException
                && t.getMessage().contains("auth failure"))
            .verify();
    }

    @Test
    void sequenceCounterIncrementsAcrossFlushes() {
        // The S3 key embeds a monotonically increasing sequence so two flushes
        // produce two distinct objects even within the same wall-clock second.
        var captured = new ArrayList<CapturedUpload>();
        var sink = S3FailedDocumentStreamSink.builder()
            .bucket("b").prefix("p/").sessionId("s").workerId("w").region("r")
            .uploader((uri, data, region) -> captured.add(new CapturedUpload(uri, data)))
            .build();

        sink.write(buildRecord("a")).subscribe();
        sink.flush().block();
        sink.write(buildRecord("b")).subscribe();
        sink.flush().block();

        assertThat(captured, hasSize(2));
        // Keys differ — verifies the sequence counter is in the key.
        assertThat(captured.get(0).s3Uri(), not(equalTo(captured.get(1).s3Uri())));
        // First flush is sequence 0, second is sequence 1 (counter starts at 0 and gets-and-increments).
        assertThat(captured.get(0).s3Uri(), containsString("-0.ndjson.gz"));
        assertThat(captured.get(1).s3Uri(), containsString("-1.ndjson.gz"));
    }

    @Test
    void s3ClientUploader_parsesUriAndIssuesPutObject() {
        // Cover the static factory s3ClientUploader: it must parse "s3://bucket/key"
        // into bucket + key and call S3AsyncClient.putObject with both set.
        var s3Client = mock(S3AsyncClient.class);
        when(s3Client.putObject(any(PutObjectRequest.class), any(AsyncRequestBody.class)))
            .thenReturn(CompletableFuture.completedFuture(null));

        var uploader = S3FailedDocumentStreamSink.s3ClientUploader(s3Client);
        assertDoesNotThrow(
            () -> uploader.upload("s3://my-bucket/path/to/object.gz", new byte[]{1, 2, 3}, "us-west-2"));

        ArgumentCaptor<PutObjectRequest> req = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(req.capture(), any(AsyncRequestBody.class));
        assertThat(req.getValue().bucket(), equalTo("my-bucket"));
        assertThat(req.getValue().key(), equalTo("path/to/object.gz"));
        assertThat(req.getValue().contentType(), equalTo("application/gzip"));
    }

    private static FailedDocumentStreamRecord buildRecord(String docId) {
        return buildRecordForIndex("idx", docId);
    }

    private static FailedDocumentStreamRecord buildRecordForIndex(String index, String docId) {
        var mapper = new ObjectMapper();
        return FailedDocumentStreamRecord.builder()
            .sessionId("s").workerId("w").targetIndex(index).documentId(docId)
            .failureType("err").failureClass(FailureClass.NON_RETRYABLE)
            .timestamp(Instant.parse("2026-05-14T12:00:00Z").toString())
            .requestItem(mapper.createObjectNode())
            .responseItem(mapper.createObjectNode())
            .build();
    }

    private static String decode(byte[] gzipped) throws IOException {
        try (var gz = new GZIPInputStream(new ByteArrayInputStream(gzipped))) {
            return new String(gz.readAllBytes());
        }
    }

    private static int countLines(byte[] gzipped) throws IOException {
        var stripped = decode(gzipped).strip();
        return stripped.isEmpty() ? 0 : stripped.split("\n").length;
    }
}
