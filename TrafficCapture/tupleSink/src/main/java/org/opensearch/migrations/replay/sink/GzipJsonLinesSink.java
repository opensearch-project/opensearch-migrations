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

/**
 * Writes tuples as gzip-compressed JSON lines to files compatible with Mountpoint S3.
 *
 * <p>Commits tuples on every {@link #onEndOfBatch()} by flushing the gzip stream
 * (via {@code syncFlush=true}) and fsyncing the underlying file. This makes the
 * compressed data durable on disk/S3 so Kafka offsets can be committed immediately,
 * while preserving the deflate dictionary across flushes for good compression.</p>
 *
 * <p>Each instance is single-threaded (one per Netty event loop). The {@code threadIndex}
 * is embedded in filenames to avoid collisions between concurrent writers.</p>
 *
 * <p>Files are rotated (finish + close + open new) when size or age thresholds are
 * exceeded. The file is only readable as valid gzip after {@code finish()} is called
 * (on rotation or close), but durability for Kafka commit safety only requires fsync.</p>
 */
@Slf4j
public class GzipJsonLinesSink implements TupleSink {
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path outputDir;
    private final long maxFileSizeBytes;
    private final Duration maxFileAge;
    private final int threadIndex;
    private final AtomicLong sequenceCounter = new AtomicLong();

    private GZIPOutputStream gzipOut;
    private FileOutputStream fileOut;
    private long bytesWritten;
    private Instant fileOpenedAt;
    private final List<CompletableFuture<Void>> pendingFutures = new ArrayList<>();

    public GzipJsonLinesSink(Path outputDir, long maxFileSizeBytes, Duration maxFileAge, int threadIndex) {
        this.outputDir = outputDir;
        this.maxFileSizeBytes = maxFileSizeBytes;
        this.maxFileAge = maxFileAge;
        this.threadIndex = threadIndex;
        try {
            java.nio.file.Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create tuple output directory: " + outputDir, e);
        }
        openNewFile();
    }

    /** Convenience constructor for single-threaded use (thread index 0). */
    public GzipJsonLinesSink(Path outputDir, long maxFileSizeBytes, Duration maxFileAge) {
        this(outputDir, maxFileSizeBytes, maxFileAge, 0);
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
        if (pendingFutures.isEmpty()) {
            return;
        }
        if (shouldRotate()) {
            rotate();
        } else {
            try {
                // syncFlush=true ensures flush() pushes all deflated data to the
                // FileOutputStream. fsync then makes it durable on disk/Mountpoint.
                gzipOut.flush();
                fileOut.getFD().sync();
            } catch (IOException e) {
                log.atError().setCause(e).setMessage("Failed to flush/sync tuple file").log();
                completeAllExceptionally(e);
                return;
            }
            completeAll();
        }
    }

    @Override
    public void onIdle() {
        if (!pendingFutures.isEmpty()) {
            onEndOfBatch();
        }
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
        gzipOut = null;
        fileOut = null;
    }

    private boolean shouldRotate() {
        return bytesWritten >= maxFileSizeBytes
            || Duration.between(fileOpenedAt, Instant.now()).compareTo(maxFileAge) >= 0;
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
        var filename = String.format("tuples-%d-%s-%d.log.gz", threadIndex, timestamp, seq);
        var path = outputDir.resolve(filename);
        try {
            fileOut = new FileOutputStream(path.toFile());
            gzipOut = new GZIPOutputStream(fileOut, true);
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
