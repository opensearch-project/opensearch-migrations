package org.opensearch.migrations.replay.sink;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GzipJsonLinesSink implements TupleSink {
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path outputDir;
    private final long maxFileSizeBytes;
    private final Duration maxFileAge;
    private final AtomicLong sequenceCounter = new AtomicLong();

    private GZIPOutputStream gzipOut;
    private FileOutputStream fileOut;
    private long bytesWritten;
    private Instant fileOpenedAt;
    private final List<CompletableFuture<Void>> pendingFutures = new ArrayList<>();

    public GzipJsonLinesSink(Path outputDir, long maxFileSizeBytes, Duration maxFileAge) {
        this.outputDir = outputDir;
        this.maxFileSizeBytes = maxFileSizeBytes;
        this.maxFileAge = maxFileAge;
        try {
            java.nio.file.Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create tuple output directory: " + outputDir, e);
        }
        openNewFile();
    }

    @Override
    public void accept(Map<String, Object> tupleMap, CompletableFuture<Void> future) {
        try {
            byte[] json = mapper.writeValueAsBytes(tupleMap);
            gzipOut.write(json);
            gzipOut.write('\n');
            bytesWritten += json.length + 1;
            pendingFutures.add(future);
        } catch (IOException e) {
            future.completeExceptionally(e);
        }
    }

    @Override
    public void onEndOfBatch() {
        try {
            gzipOut.flush();
        } catch (IOException e) {
            log.atError().setCause(e).setMessage("Failed to flush gzip stream").log();
        }
        maybeRotate();
    }

    @Override
    public void onIdle() {
        maybeRotate();
    }

    @Override
    public void close() {
        if (gzipOut == null) {
            return;
        }
        try {
            gzipOut.finish();
            fileOut.getFD().sync();
            fileOut.close();
        } catch (IOException e) {
            log.atError().setCause(e).setMessage("Failed to close tuple file").log();
            completeAllExceptionally(e);
            return;
        }
        completeAll();
    }

    private void maybeRotate() {
        if (pendingFutures.isEmpty()) {
            return;
        }
        if (bytesWritten >= maxFileSizeBytes
            || Duration.between(fileOpenedAt, Instant.now()).compareTo(maxFileAge) >= 0) {
            rotate();
        }
    }

    private void rotate() {
        try {
            gzipOut.finish();
            fileOut.getFD().sync();
            fileOut.close();
        } catch (IOException e) {
            log.atError().setCause(e).setMessage("Failed to rotate tuple file").log();
            completeAllExceptionally(e);
            openNewFile();
            return;
        }
        completeAll();
        openNewFile();
    }

    private void openNewFile() {
        var timestamp = TIMESTAMP_FORMAT.format(Instant.now());
        var seq = sequenceCounter.getAndIncrement();
        var filename = String.format("tuples-%s-%d.log.gz", timestamp, seq);
        var path = outputDir.resolve(filename);
        try {
            fileOut = new FileOutputStream(path.toFile());
            gzipOut = new GZIPOutputStream(fileOut);
            bytesWritten = 0;
            fileOpenedAt = Instant.now();
            log.atInfo().setMessage("Opened new tuple file: {}").addArgument(path).log();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to open tuple file: " + path, e);
        }
    }

    private void completeAll() {
        pendingFutures.forEach(f -> f.complete(null));
        pendingFutures.clear();
    }

    private void completeAllExceptionally(Throwable t) {
        pendingFutures.forEach(f -> f.completeExceptionally(t));
        pendingFutures.clear();
    }
}
