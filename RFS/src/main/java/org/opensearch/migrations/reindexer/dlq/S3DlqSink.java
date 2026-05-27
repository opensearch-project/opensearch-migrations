package org.opensearch.migrations.reindexer.dlq;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * DLQ sink backed by S3. Buffers gzipped NDJSON records in memory and uploads
 * completed objects to S3 on {@link #flush()} and {@link #close()}.
 *
 * <p>For testing, an {@link S3Uploader} can be injected instead of a real
 * {@link S3AsyncClient} to capture uploads in-process.
 *
 * <p>S3 key layout:
 * <pre>
 *   s3://&lt;bucket&gt;/&lt;prefix&gt;session=&lt;sessionId&gt;/worker=&lt;workerId&gt;/dlq-&lt;ts&gt;-&lt;seq&gt;.ndjson.gz
 * </pre>
 */
@Slf4j
public class S3DlqSink implements DlqSink {
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @FunctionalInterface
    public interface S3Uploader {
        void upload(String s3Uri, byte[] data, String region) throws IOException;
    }

    private final String bucket;
    private final String prefix;
    private final String sessionId;
    private final String workerId;
    private final String region;
    private final String location;
    private final S3Uploader uploader;
    private final AtomicLong sequenceCounter = new AtomicLong();

    private ByteArrayOutputStream buffer;
    private GZIPOutputStream gzipOut;
    private boolean closed;

    @Builder
    public S3DlqSink(
        String bucket,
        String prefix,
        String sessionId,
        String workerId,
        String region,
        S3Uploader uploader
    ) {
        this.bucket = bucket;
        this.prefix = normalizePrefix(prefix);
        this.sessionId = sessionId;
        this.workerId = workerId;
        this.region = region;
        this.uploader = uploader;
        this.location = "s3://" + bucket + "/" + this.prefix + "session=" + sessionId + "/";
    }

    private static String normalizePrefix(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        return raw.endsWith("/") ? raw : raw + "/";
    }

    @Override
    public Mono<Void> write(DlqRecord dlqRecord) {
        if (closed) {
            return Mono.error(new IllegalStateException("S3DlqSink is closed"));
        }
        try {
            if (gzipOut == null) {
                buffer = new ByteArrayOutputStream();
                gzipOut = new GZIPOutputStream(buffer);
            }
            byte[] json = MAPPER.writeValueAsBytes(dlqRecord);
            gzipOut.write(json);
            gzipOut.write('\n');
        } catch (IOException e) {
            return Mono.error(e);
        }
        return Mono.empty();
    }

    @Override
    public Mono<Void> flush() {
        if (gzipOut == null) {
            return Mono.empty();
        }
        try {
            gzipOut.finish();
            gzipOut.close();
        } catch (IOException e) {
            return Mono.error(e);
        }
        byte[] data = buffer.toByteArray();
        gzipOut = null;
        buffer = null;

        var key = buildS3Key();
        var s3Uri = "s3://" + bucket + "/" + key;

        try {
            uploader.upload(s3Uri, data, region);
            log.atInfo().setMessage("DLQ upload complete: {} ({} bytes)").addArgument(s3Uri).addArgument(data.length).log();
        } catch (Exception e) {
            log.atError().setCause(e).setMessage("Failed to upload DLQ object {}").addArgument(s3Uri).log();
            return Mono.error(e);
        }
        return Mono.empty();
    }

    @Override
    public String getLocation() {
        return location;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (gzipOut != null) {
            flush().block();
        }
    }

    private String buildS3Key() {
        var timestamp = TIMESTAMP_FORMAT.format(Instant.now());
        var seq = sequenceCounter.getAndIncrement();
        return prefix + "session=" + sessionId + "/worker=" + workerId
            + "/dlq-" + timestamp + "-" + seq + ".ndjson.gz";
    }

    /**
     * Creates an {@link S3Uploader} backed by the given {@link S3AsyncClient}.
     * Parses the {@code s3://bucket/key} URI and issues a blocking PutObject.
     */
    public static S3Uploader s3ClientUploader(S3AsyncClient s3Client) {
        return (s3Uri, data, region) -> {
            var stripped = s3Uri.replaceFirst("^s3://", "");
            var slashIdx = stripped.indexOf('/');
            var putBucket = stripped.substring(0, slashIdx);
            var putKey = stripped.substring(slashIdx + 1);
            var request = PutObjectRequest.builder()
                .bucket(putBucket)
                .key(putKey)
                .contentType("application/gzip")
                .build();
            s3Client.putObject(request, AsyncRequestBody.fromBytes(data)).join();
        };
    }
}
