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
 * <p>Uses concatenated gzip members (RFC 1952 §2.2) to enable streaming commits:
 * each {@link #onEndOfBatch()} finishes the current gzip member, fsyncs the file,
 * and completes all pending futures immediately. A new gzip member is started for
 * subsequent writes. Standard {@code GZIPInputStream} reads concatenated members
 * seamlessly.</p>
 *
 * <p>Files are rotated to a new path when size or age thresholds are exceeded.</p>
 */
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
        if (pendingFutures.isEmpty()) {
            return;
        }
        try {
            // Finish the current gzip member — writes a valid gzip trailer so the file
            // is readable up to this point. Then fsync to flush to Mountpoint/disk.
            gzipOut.finish();
            fileOut.getFD().sync();
        } catch (IOException e) {
            log.atError().setCause(e).setMessage("Failed to flush/sync tuple file").log();
            completeAllExceptionally(e);
            return;
        }
        completeAll();

        // Rotate to a new file if thresholds are met, otherwise start a new gzip member
        // on the same FileOutputStream (concatenated gzip per RFC 1952).
        if (shouldRotate()) {
            closeFileQuietly();
            openNewFile();
        } else {
            try {
                gzipOut = new GZIPOutputStream(fileOut, true);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to start new gzip member", e);
            }
        }
    }

    @Override
    public void onIdle() {
        // Flush any pending tuples that haven't been committed yet
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

    private void closeFileQuietly() {
        try {
            fileOut.close();
        } catch (IOException e) {
            log.atWarn().setCause(e).setMessage("Failed to close rotated tuple file").log();
        }
    }

    private void openNewFile() {
        var timestamp = TIMESTAMP_FORMAT.format(Instant.now());
        var seq = sequenceCounter.getAndIncrement();
        var filename = String.format("tuples-%s-%d.log.gz", timestamp, seq);
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
