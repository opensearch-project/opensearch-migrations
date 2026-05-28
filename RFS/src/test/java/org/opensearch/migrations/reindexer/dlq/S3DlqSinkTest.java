package org.opensearch.migrations.reindexer.dlq;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
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
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class S3DlqSinkTest {

    private record CapturedUpload(String s3Uri, byte[] data) {}

    @Test
    void writesGzippedNdjsonAndIsolatesBySession() throws Exception {
        var captured = new ArrayList<CapturedUpload>();
        S3DlqSink.S3Uploader testUploader = (uri, data, region) -> captured.add(new CapturedUpload(uri, data));

        var sink = S3DlqSink.builder()
            .bucket("my-bucket")
            .prefix("rfs-dlq/")
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

        sink.write(DlqRecord.builder()
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
        assertThat(captured.get(0).s3Uri(), startsWith("s3://my-bucket/rfs-dlq/session=sess-A/worker=worker-1/dlq-"));

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
        assertThat(sink.getLocation(), equalTo("s3://my-bucket/rfs-dlq/session=sess-A/"));
    }

    @Test
    void locationReflectsSessionPrefixWithoutTrailingSlashOnInput() {
        var sink = S3DlqSink.builder()
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
        // mandatory "rfs-dlq/" segment.
        var sink = S3DlqSink.builder()
            .bucket("b").prefix("").sessionId("s").workerId("w").region("r")
            .uploader((uri, data, region) -> {}).build();
        assertThat(sink.getLocation(), equalTo("s3://b/session=s/"));
    }

    @Test
    void nullPrefixIsTreatedAsEmpty() {
        var sink = S3DlqSink.builder()
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
        var sink = S3DlqSink.builder()
            .bucket("b").prefix("p/").sessionId("s").workerId("w").region("r")
            .uploader((uri, data, region) -> captured.add(new CapturedUpload(uri, data)))
            .build();

        sink.flush().block();

        assertThat(captured, hasSize(0));
    }

    @Test
    void writeAfterCloseReturnsError() {
        var sink = S3DlqSink.builder()
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
        var sink = S3DlqSink.builder()
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
        var sink = S3DlqSink.builder()
            .bucket("b").prefix("p/").sessionId("s").workerId("w").region("r")
            .uploader((uri, data, region) -> {}).build();
        sink.close();   // no exception
        assertThat(sink.getLocation(), equalTo("s3://b/p/session=s/"));
    }

    @Test
    void closeAfterWritesUploadsBufferedData() {
        var captured = new ArrayList<CapturedUpload>();
        var sink = S3DlqSink.builder()
            .bucket("b").prefix("p/").sessionId("s").workerId("w").region("r")
            .uploader((uri, data, region) -> captured.add(new CapturedUpload(uri, data)))
            .build();
        sink.write(buildRecord("doc-1")).subscribe();
        sink.close();
        assertThat(captured, hasSize(1));
    }

    @Test
    void uploaderIoExceptionPropagatesAsMonoError() {
        var sink = S3DlqSink.builder()
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
        var sink = S3DlqSink.builder()
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
        var sink = S3DlqSink.builder()
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

        var uploader = S3DlqSink.s3ClientUploader(s3Client);
        assertDoesNotThrow(
            () -> uploader.upload("s3://my-bucket/path/to/object.gz", new byte[]{1, 2, 3}, "us-west-2"));

        ArgumentCaptor<PutObjectRequest> req = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(req.capture(), any(AsyncRequestBody.class));
        assertThat(req.getValue().bucket(), equalTo("my-bucket"));
        assertThat(req.getValue().key(), equalTo("path/to/object.gz"));
        assertThat(req.getValue().contentType(), equalTo("application/gzip"));
    }

    private static DlqRecord buildRecord(String docId) {
        var mapper = new ObjectMapper();
        return DlqRecord.builder()
            .sessionId("s").workerId("w").targetIndex("idx").documentId(docId)
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
}
