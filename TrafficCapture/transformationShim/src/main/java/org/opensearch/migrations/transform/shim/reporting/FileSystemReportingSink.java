package org.opensearch.migrations.transform.shim.reporting;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link ReportingSink} implementation that writes {@link ValidationDocument} instances
 * as individual JSON files to a configurable local directory.
 *
 * <p>Uses a bounded {@link LinkedBlockingQueue} to decouple the calling thread from disk I/O,
 * and a single background daemon thread to consume items and write files. This keeps
 * {@link #submit(ValidationDocument)} non-blocking so it is safe to call inline on the
 * Netty event loop.</p>
 */
public class FileSystemReportingSink implements ReportingSink {

    private static final Logger log = LoggerFactory.getLogger(FileSystemReportingSink.class);
    private static final int DEFAULT_BUFFER_CAPACITY = 1024;

    /**
     * Poison pill used to signal flush/close to the background thread.
     * Uses a sentinel ValidationDocument instance that is identity-compared.
     */
    private static final ValidationDocument POISON_PILL = new ValidationDocument(
        null, null, null, null, null, null,
        null, null, null, null, null, null, null,
        null, null, null
    );

    private final Path outputDirectory;
    private final LinkedBlockingQueue<ValidationDocument> queue;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final ObjectMapper objectMapper;
    private final Thread writerThread;

    /** Set by flush()/close(), completed by the writer thread when it hits the poison pill. */
    private final AtomicReference<CompletableFuture<Void>> flushFuture = new AtomicReference<>();

    /**
     * Creates a new sink with the default buffer capacity of 1024.
     *
     * @param outputDirectory the directory where JSON files are written
     */
    public FileSystemReportingSink(Path outputDirectory) {
        this(outputDirectory, DEFAULT_BUFFER_CAPACITY);
    }

    /**
     * Creates a new sink with the specified buffer capacity.
     *
     * @param outputDirectory the directory where JSON files are written; created if it does not exist
     * @param bufferCapacity  the maximum number of documents the in-memory queue can hold
     * @throws IllegalArgumentException if the path exists but is a regular file
     * @throws UncheckedIOException     if the directory cannot be created
     */
    public FileSystemReportingSink(Path outputDirectory, int bufferCapacity) {
        if (Files.isRegularFile(outputDirectory)) {
            throw new IllegalArgumentException(
                "Output path exists but is a regular file: " + outputDirectory);
        }
        try {
            Files.createDirectories(outputDirectory);
        } catch (IOException e) {
            throw new UncheckedIOException(
                "Failed to create output directory: " + outputDirectory, e);
        }
        this.outputDirectory = outputDirectory;
        this.queue = new LinkedBlockingQueue<>(bufferCapacity);
        this.objectMapper = new ObjectMapper()
            .setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);

        this.writerThread = new Thread(this::writeLoop, "fs-metrics-sink-writer");
        this.writerThread.setDaemon(true);
        this.writerThread.start();
    }

    @Override
    public void submit(ValidationDocument document) {
        if (document == null) {
            return;
        }
        if (closed.get()) {
            log.warn("Document submitted after close, discarding: {}", document.requestId());
            return;
        }
        if (!queue.offer(document)) {
            log.warn("Buffer full, discarding document: {}", document.requestId());
        }
    }

    @Override
    public void flush() {
        if (closed.get()) {
            return;
        }
        this.flushFuture.set(new CompletableFuture<>());
        enqueuePoisonPill();
        awaitFlushFuture();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        this.flushFuture.set(new CompletableFuture<>());
        enqueuePoisonPill();
        awaitFlushFuture();
    }

    @SuppressWarnings({"java:S2142", "java:S2925"}) // interrupt handling is in enqueuePoisonPill; timeout is intentional safety net
    private void awaitFlushFuture() {
        try {
            this.flushFuture.get().get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Flush did not complete normally", e);
        }
    }

    private void enqueuePoisonPill() {
        try {
            if (!queue.offer(POISON_PILL, 10, TimeUnit.SECONDS)) {
                log.warn("Timed out enqueuing poison pill, queue may be full");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void writeLoop() {
        try {
            while (true) {
                ValidationDocument doc = queue.take();
                if (doc == POISON_PILL) {
                    this.flushFuture.get().complete(null);
                    if (closed.get()) {
                        return;
                    }
                    continue;
                }
                writeDocument(doc);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void writeDocument(ValidationDocument document) {
        try {
            byte[] jsonBytes = objectMapper.writeValueAsBytes(document);
            String timestamp = document.timestamp() != null
                ? document.timestamp().replace(":", "-")
                : "unknown";
            String requestId = document.requestId() != null
                ? document.requestId()
                : "unknown";
            String fileName = timestamp + "_" + requestId + ".json";
            Path filePath = outputDirectory.resolve(fileName);
            Files.write(filePath, jsonBytes);
        } catch (Exception e) {
            log.error("Failed to write document to disk, discarding: {}",
                document.requestId(), e);
        }
    }
}
