package org.opensearch.migrations.reindexer.faileddocumentstream;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import reactor.core.publisher.Mono;

/** In-memory {@link FailedDocumentStreamSink} that keeps every record it is given. */
public class CapturingFailedDocumentStreamSink implements FailedDocumentStreamSink {
    private final List<FailedDocumentStreamRecord> records = new CopyOnWriteArrayList<>();
    private final AtomicInteger flushes = new AtomicInteger();

    @Override
    public Mono<Void> write(FailedDocumentStreamRecord failedDocumentStreamRecord) {
        records.add(failedDocumentStreamRecord);
        return Mono.empty();
    }

    @Override
    public Mono<Void> flush() {
        flushes.incrementAndGet();
        return Mono.empty();
    }

    @Override
    public String getLocation() {
        return "memory://failed-document-stream/";
    }

    @Override
    public void close() {
        // Nothing to release.
    }

    public List<FailedDocumentStreamRecord> getRecords() {
        return List.copyOf(records);
    }

    public int getFlushCount() {
        return flushes.get();
    }
}
