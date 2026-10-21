package org.opensearch.migrations.reindexer.doccount;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.opensearch.migrations.bulkload.common.ObjectMapperFactory;
import org.opensearch.migrations.s3sink.RotatingGzipS3ObjectWriter;
import org.opensearch.migrations.s3sink.RotationPolicy;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/** Writes shard counts as gzip NDJSON; flush before completing the work item. */
public class S3ShardDocCountSink implements ShardDocCountSink {
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final ObjectMapper MAPPER = ObjectMapperFactory.createDefaultMapper();

    private final String location;
    private final RotatingGzipS3ObjectWriter<ShardDocCountRecord> writer;
    private final Scheduler scheduler = Schedulers.newSingle("shard-doc-count-sink", true);
    private final AtomicBoolean closed = new AtomicBoolean();

    @Builder
    public S3ShardDocCountSink(
        String bucket,
        String prefix,
        String sessionId,
        String workerId,
        RotatingGzipS3ObjectWriter.ObjectUploader uploader
    ) {
        var normalizedPrefix = normalizePrefix(prefix);
        this.location = "s3://" + bucket + "/" + normalizedPrefix + "session=" + sessionId + "/";
        this.writer = new RotatingGzipS3ObjectWriter<>(
            uploader,
            bucket,
            (now, seq) -> normalizedPrefix + "session=" + sessionId + "/worker=" + workerId
                + "/shard-doc-counts-" + TIMESTAMP_FORMAT.format(now) + "-" + seq + ".ndjson.gz",
            MAPPER::writeValueAsBytes,
            RotationPolicy.ofBytes(0),
            Duration.ZERO,
            1,
            "sdc-");
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) return "";
        return prefix.endsWith("/") ? prefix : prefix + "/";
    }

    @Override
    public Mono<Void> write(ShardDocCountRecord countRecord) {
        return onWriter(() -> {
            var appended = writer.write(countRecord);
            // A successful append remains pending until flush uploads its object.
            return appended.isCompletedExceptionally() ? Mono.fromFuture(appended) : Mono.empty();
        });
    }

    @Override
    public Mono<Void> flush() {
        return onWriter(() -> Mono.fromFuture(writer.flush()));
    }

    private Mono<Void> onWriter(Supplier<Mono<Void>> operation) {
        return Mono.defer(() -> closed.get()
            ? Mono.error(new IllegalStateException("S3ShardDocCountSink is closed"))
            : Mono.defer(operation).subscribeOn(scheduler));
    }

    @Override
    public String getLocation() {
        return location;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                Mono.fromRunnable(writer::close).subscribeOn(scheduler).block();
            } finally {
                scheduler.dispose();
            }
        }
    }
}
