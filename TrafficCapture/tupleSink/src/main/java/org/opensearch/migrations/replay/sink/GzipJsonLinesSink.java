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
 * Writes tuples as gzip-compressed JSON lines to local files.
 *
 * <p>Tuples accumulate in a gzip buffer until either the uncompressed byte threshold
 * ({@code rotateAfterBytes}) or the time threshold ({@code maxFileAge}) is reached,
 * at which point the file is finished, fsynced, closed, and a new file is opened.
 * This batching preserves the gzip deflate dictionary across tuples within a file,
 * achieving ~39x compression on repetitive JSON.</p>
 *
 * <p>Each instance is single-threaded (one per Netty event loop). The {@code threadIndex}
 * is embedded in filenames to avoid collisions between concurrent writers.</p>
 *
 * <p><b>Known limitation — no idle age-flush.</b> Like the original {@link S3TupleSink},
 * the {@code maxFileAge} threshold is only re-evaluated inside {@link #accept} (via
 * {@link #shouldRotate}). If this sink stops receiving tuples, the trailing batch is never
 * rotated and its pending futures never complete — which, in a replay context, would pin the
 * Kafka consumer offset (the commit is gated on durable tuple output). {@code S3TupleSink} was
 * fixed by self-scheduling the age flush on its own worker thread; this sink does NOT yet do
 * that. It is currently test-only (no production {@code new GzipJsonLinesSink}), so this is a
 * latent gap, not a live bug — but anything that wires it into a running replayer must add an
 * equivalent self-scheduled/periodic age flush first. See {@code S3TupleSink}'s scheduled
 * {@code periodicFlushOnWorker} for the pattern.</p>
 */
@Slf4j
public class GzipJsonLinesSink implements TupleSink {
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path outputDir;
    private final long rotateAfterBytes;
    private final Duration maxFileAge;
    private final int threadIndex;
    private final AtomicLong sequenceCounter = new AtomicLong();

    private GZIPOutputStream gzipOut;
    private FileOutputStream fileOut;
    private long uncompressedBytes;
    private Instant fileOpenedAt;
    private final List<CompletableFuture<Void>> pendingFutures = new ArrayList<>();

    public GzipJsonLinesSink(Path outputDir, long rotateAfterBytes, Duration maxFileAge, int threadIndex) {
        this.outputDir = outputDir;
        this.rotateAfterBytes = rotateAfterBytes;
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
    public GzipJsonLinesSink(Path outputDir, long rotateAfterBytes, Duration maxFileAge) {
        this(outputDir, rotateAfterBytes, maxFileAge, 0);
    }

    @Override
    public void accept(Map<String, Object> tupleMap, CompletableFuture<Void> future) {
        try {
            byte[] json = mapper.writeValueAsBytes(tupleMap);
            gzipOut.write(json);
            gzipOut.write('\n');
            uncompressedBytes += json.length + 1;
            pendingFutures.add(future);
        } catch (IOException e) {
            future.completeExceptionally(e);
            return;
        }
        if (shouldRotate()) {
            rotate();
        }
    }

    @Override
    public void flush() {
        if (pendingFutures.isEmpty()) {
            return;
        }
        rotate();
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
        // NOTE: the maxFileAge branch only fires when this is evaluated — and it is only
        // evaluated from accept(). A sink that goes idle will therefore never rotate on age
        // alone; the trailing batch waits for the next tuple (or close()). S3TupleSink solved
        // this with a self-scheduled age flush; replicate that here before using this sink in a
        // running replayer. See the class-level "Known limitation" note.
        return uncompressedBytes >= rotateAfterBytes
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
            uncompressedBytes = 0;
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
