package org.opensearch.migrations.reindexer.dlq;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.zip.GZIPInputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class S3DlqSinkTest {

    @Test
    void writesGzippedNdjsonAndIsolatesBySession() throws Exception {
        var s3 = mock(S3AsyncClient.class);
        var captureRequests = ArgumentCaptor.forClass(PutObjectRequest.class);
        var captureBody = ArgumentCaptor.forClass(AsyncRequestBody.class);
        // The S3 client returns a successful future after the gzip stream is drained.
        var capturedBytes = new ArrayList<byte[]>();
        when(s3.putObject(captureRequests.capture(), captureBody.capture()))
            .thenAnswer(invocation -> {
                AsyncRequestBody body = invocation.getArgument(1);
                CompletableFuture<PutObjectResponse> future = new CompletableFuture<>();
                body.subscribe(new Subscriber<ByteBuffer>() {
                    private final java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    private Subscription subscription;
                    @Override public void onSubscribe(Subscription s) {
                        this.subscription = s;
                        s.request(Long.MAX_VALUE);
                    }
                    @Override public void onNext(ByteBuffer bb) {
                        byte[] arr = new byte[bb.remaining()];
                        bb.get(arr);
                        baos.write(arr, 0, arr.length);
                    }
                    @Override public void onError(Throwable t) {
                        future.completeExceptionally(t);
                    }
                    @Override public void onComplete() {
                        capturedBytes.add(baos.toByteArray());
                        future.complete(PutObjectResponse.builder().build());
                    }
                });
                return future;
            });

        var sink = S3DlqSink.builder()
            .s3Client(s3)
            .bucket("my-bucket")
            .prefix("rfs-dlq/")
            .sessionId("sess-A")
            .workerId("worker-1")
            .policy(S3DlqSink.RotationPolicy.onePerFlush())
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
            .build()).block();

        sink.flush().block();
        sink.close();

        // One object uploaded, under the right session prefix.
        var requests = captureRequests.getAllValues();
        assertThat(requests, hasSize(1));
        var put = requests.get(0);
        assertThat(put.bucket(), equalTo("my-bucket"));
        assertThat(put.key(), startsWith("rfs-dlq/session=sess-A/worker=worker-1/dlq-"));
        assertThat(put.contentType(), equalTo("application/gzip"));

        // The gzip body decodes to one NDJSON line containing the record we wrote.
        assertThat(capturedBytes, hasSize(1));
        var decoded = decode(capturedBytes.get(0));
        assertThat(decoded, containsString("\"documentId\":\"doc-1\""));
        assertThat(decoded, containsString("\"failureClass\":\"NON_RETRYABLE\""));
        assertThat(decoded, containsString("\"failureType\":\"mapper_parsing_exception\""));

        // Each NDJSON line is parseable as a DlqRecord-shaped object.
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
        var s3 = mock(S3AsyncClient.class);
        var sink = S3DlqSink.builder()
            .s3Client(s3)
            .bucket("b")
            .prefix("foo/bar") // no trailing slash on purpose
            .sessionId("sess-XYZ")
            .workerId("w")
            .build();
        assertThat(sink.getLocation(), equalTo("s3://b/foo/bar/session=sess-XYZ/"));
        assertThat(sink, notNullValue());
    }

    private static String decode(byte[] gzipped) throws IOException {
        try (var gz = new GZIPInputStream(new ByteArrayInputStream(gzipped))) {
            return new String(gz.readAllBytes());
        }
    }

    private static List<byte[]> dummy() { return new ArrayList<>(); }
}
