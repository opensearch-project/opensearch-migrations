package org.opensearch.migrations.reindexer.doccount;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.zip.GZIPInputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Covers the per-shard doc-count sink against an in-memory uploader, so the record schema, the
 * NDJSON gzip layout, the S3 key layout and the flush-failure contract are all verified without
 * needing S3 or LocalStack.
 */
class S3ShardDocCountSinkTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Captures uploaded objects in memory instead of calling S3. */
    private static class CapturingUploader {
        final List<String> keys = new ArrayList<>();
        final List<byte[]> bodies = new ArrayList<>();
        volatile boolean fail = false;

        org.opensearch.migrations.s3sink.RotatingGzipS3ObjectWriter.ObjectUploader asUploader() {
            return (bucket, key, file) -> {
                if (fail) {
                    return CompletableFuture.failedFuture(new IOException("simulated upload failure"));
                }
                try {
                    synchronized (this) {
                        keys.add(key);
                        bodies.add(java.nio.file.Files.readAllBytes(file));
                    }
                    return CompletableFuture.completedFuture(null);
                } catch (IOException e) {
                    return CompletableFuture.failedFuture(e);
                }
            };
        }

        List<JsonNode> records() throws IOException {
            var out = new ArrayList<JsonNode>();
            for (byte[] gz : bodies) {
                try (var in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
                    var text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    for (String line : text.split("\n")) {
                        if (!line.isBlank()) out.add(MAPPER.readTree(line));
                    }
                }
            }
            return out;
        }
    }

    private static ShardDocCountRecord countRecord(String index, int shard, long total) {
        // Lucene total exceeds the root total, as it does on any shard with nested documents.
        return countRecord(index, shard, total, total * 3);
    }

    private static ShardDocCountRecord countRecord(String index, int shard, long total, long luceneTotal) {
        return ShardDocCountRecord.builder()
            .sessionId("sess-1")
            .workerId("worker-1")
            .workItemId("wi-1")
            .indexName(index)
            .shardNumber(shard)
            .liveDocCount(total)
            .liveLuceneDocCount(luceneTotal)
            .docsThisGeneration(total)
            .docsPriorGenerations(0)
            .shardComplete(true)
            .timestamp("2026-07-29T00:00:00Z")
            .build();
    }

    private static S3ShardDocCountSink sink(CapturingUploader uploader) {
        return S3ShardDocCountSink.builder()
            .bucket("my-bucket")
            .prefix("counts")
            .sessionId("sess-1")
            .workerId("worker-1")
            .region("us-east-1")
            .uploader(uploader.asUploader())
            .build();
    }

    @Test
    void writesRecordsAsGzippedNdjsonAndReportsLocation() throws Exception {
        var uploader = new CapturingUploader();
        try (var s = sink(uploader)) {
            Assertions.assertEquals("s3://my-bucket/counts/session=sess-1/", s.getLocation(),
                "location must point at the session prefix and normalize the missing trailing slash");
            s.write(countRecord("idx", 0, 5)).block();
            s.write(countRecord("idx", 1, 7)).block();
            s.flush().block();
        }

        var records = uploader.records();
        Assertions.assertEquals(2, records.size(), "both records must be durable after flush");
        Assertions.assertEquals(5, records.get(0).get("liveDocCount").asLong());
        Assertions.assertEquals(0, records.get(0).get("shardNumber").asInt());
        Assertions.assertEquals(7, records.get(1).get("liveDocCount").asLong());
        Assertions.assertEquals(1, records.get(1).get("shardNumber").asInt());
        Assertions.assertEquals("idx", records.get(0).get("indexName").asText());
        Assertions.assertTrue(records.get(0).get("shardComplete").asBoolean());

        // Both counts must round-trip: the root count comparable to _count, and the Lucene count
        // comparable to _cat/indices docs.count.
        Assertions.assertEquals(15, records.get(0).get("liveLuceneDocCount").asLong());
        Assertions.assertEquals(21, records.get(1).get("liveLuceneDocCount").asLong());
        Assertions.assertTrue(
            records.get(0).get("liveLuceneDocCount").asLong() > records.get(0).get("liveDocCount").asLong(),
            "the Lucene count must be able to exceed the root count in the serialized record");
    }

    @Test
    void keyLayoutIsSessionAndWorkerScoped() throws Exception {
        var uploader = new CapturingUploader();
        try (var s = sink(uploader)) {
            s.write(countRecord("idx", 0, 1)).block();
            s.flush().block();
        }
        Assertions.assertEquals(1, uploader.keys.size());
        var key = uploader.keys.get(0);
        Assertions.assertTrue(key.startsWith("counts/session=sess-1/worker=worker-1/shard-doc-counts-"),
            "unexpected key layout: " + key);
        Assertions.assertTrue(key.endsWith(".ndjson.gz"), "unexpected key suffix: " + key);
    }

    @Test
    void flushFailurePropagatesSoTheWorkItemIsNotCompleted() {
        var uploader = new CapturingUploader();
        uploader.fail = true;
        try (var s = sink(uploader)) {
            s.write(countRecord("idx", 0, 5)).block();
            // The gating flush must surface the upload failure. Swallowing it here would let the
            // caller mark the work item complete and silently lose the count.
            Assertions.assertThrows(Exception.class, () -> s.flush().block(),
                "a failed upload must fail the gating flush");
        }
    }

    @Test
    void writeAfterCloseIsRejected() {
        var uploader = new CapturingUploader();
        var s = sink(uploader);
        s.close();
        Assertions.assertThrows(IllegalStateException.class,
            () -> s.write(countRecord("idx", 0, 1)).block(),
            "writes after close must fail rather than being silently dropped");
    }

    @Test
    void closeIsIdempotent() {
        var uploader = new CapturingUploader();
        var s = sink(uploader);
        s.close();
        Assertions.assertDoesNotThrow(s::close, "close must be safe to call twice");
    }

    @Test
    void emptyPrefixIsHandled() throws Exception {
        var uploader = new CapturingUploader();
        var s = S3ShardDocCountSink.builder()
            .bucket("b").prefix("").sessionId("s").workerId("w")
            .region("us-east-1").uploader(uploader.asUploader()).build();
        try (s) {
            Assertions.assertEquals("s3://b/session=s/", s.getLocation());
            s.write(countRecord("idx", 0, 1)).block();
            s.flush().block();
        }
        Assertions.assertTrue(uploader.keys.get(0).startsWith("session=s/worker=w/"),
            "unexpected key: " + uploader.keys.get(0));
    }
}
