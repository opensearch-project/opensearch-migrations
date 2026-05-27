package org.opensearch.migrations.reindexer.dlq;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.zip.GZIPInputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

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

    private static String decode(byte[] gzipped) throws IOException {
        try (var gz = new GZIPInputStream(new ByteArrayInputStream(gzipped))) {
            return new String(gz.readAllBytes());
        }
    }
}
