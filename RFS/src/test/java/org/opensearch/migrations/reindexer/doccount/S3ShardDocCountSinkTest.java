package org.opensearch.migrations.reindexer.doccount;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class S3ShardDocCountSinkTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        return ShardDocCountRecord.builder()
            .sessionId("sess-1")
            .workerId("worker-1")
            .workItemId("wi-1")
            .indexName(index)
            .shardNumber(shard)
            .snapshotName("snap-1")
            .liveLuceneDocCount(total)
            .timestamp("2026-07-29T00:00:00Z")
            .build();
    }

    private static S3ShardDocCountSink sink(CapturingUploader uploader) {
        return S3ShardDocCountSink.builder()
            .bucket("my-bucket")
            .prefix("counts")
            .sessionId("sess-1")
            .workerId("worker-1")
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
        Assertions.assertEquals(5, records.get(0).get("liveLuceneDocCount").asLong());
        Assertions.assertEquals(0, records.get(0).get("shardNumber").asInt());
        Assertions.assertEquals(7, records.get(1).get("liveLuceneDocCount").asLong());
        Assertions.assertEquals(1, records.get(1).get("shardNumber").asInt());
        Assertions.assertEquals("idx", records.get(0).get("indexName").asText());
        Assertions.assertEquals("snap-1", records.get(0).get("snapshotName").asText());
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
    void unknownCountsAreNullAndEmptyShardCountsAreZero() throws Exception {
        var uploader = new CapturingUploader();
        try (var s = sink(uploader)) {
            s.write(ShardDocCountRecord.builder().indexName("unknown").build()).block();
            s.write(countRecord("empty", 0, 0)).block();
            s.flush().block();
        }
        var records = uploader.records();
        Assertions.assertTrue(records.get(0).get("liveLuceneDocCount").isNull());
        Assertions.assertEquals(0, records.get(1).get("liveLuceneDocCount").asLong());
    }

    @Test
    void flushWaitsForUploadCompletion() throws Exception {
        var started = new CountDownLatch(1);
        var uploaded = new CompletableFuture<Void>();
        try (var sink = S3ShardDocCountSink.builder()
            .bucket("bucket").sessionId("session").workerId("worker")
            .uploader((bucket, key, file) -> {
                started.countDown();
                return uploaded;
            }).build()) {
            try {
                sink.write(countRecord("idx", 0, 5)).block();
                var flushed = sink.flush().toFuture();
                Assertions.assertTrue(started.await(10, TimeUnit.SECONDS));
                Assertions.assertFalse(flushed.isDone());
                uploaded.complete(null);
                flushed.get(10, TimeUnit.SECONDS);
            } finally {
                uploaded.complete(null);
            }
        }
    }

    @Test
    void flushFailurePropagatesSoTheWorkItemIsNotCompleted() {
        var uploader = new CapturingUploader();
        uploader.fail = true;
        try (var s = sink(uploader)) {
            s.write(countRecord("idx", 0, 5)).block();
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
            .uploader(uploader.asUploader()).build();
        try (s) {
            Assertions.assertEquals("s3://b/session=s/", s.getLocation());
            s.write(countRecord("idx", 0, 1)).block();
            s.flush().block();
        }
        Assertions.assertTrue(uploader.keys.get(0).startsWith("session=s/worker=w/"),
            "unexpected key: " + uploader.keys.get(0));
    }
}
