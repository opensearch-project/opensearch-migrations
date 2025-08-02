package org.opensearch.migrations.bulkload.common;

import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.opensearch.migrations.bulkload.worker.WorkItemCursor;
import org.opensearch.migrations.reindexer.tracing.IDocumentMigrationContexts.IDocumentReindexContext;
import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.transform.NoopTransformerProvider;
import org.opensearch.migrations.transform.ThreadSafeTransformerWrapper;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Slf4j
public class DocumentReindexer {
    private static final Supplier<IJsonTransformer> NOOP_TRANSFORMER_SUPPLIER = () -> new NoopTransformerProvider().createTransformer(null);

    protected final OpenSearchClient client;
    private final int maxDocsPerBulkRequest;
    private final long maxBytesPerBulkRequest;
    private final int maxConcurrentWorkItems;
    private final ThreadSafeTransformerWrapper threadSafeTransformer;
    private final boolean isNoopTransformer;

    public DocumentReindexer(OpenSearchClient client,
               int maxDocsPerBulkRequest,
               long maxBytesPerBulkRequest,
               int maxConcurrentWorkItems,
               Supplier<IJsonTransformer> transformerSupplier) {
        this.client = client;
        this.maxDocsPerBulkRequest = maxDocsPerBulkRequest;
        this.maxBytesPerBulkRequest = maxBytesPerBulkRequest;
        this.maxConcurrentWorkItems = maxConcurrentWorkItems;
        this.isNoopTransformer = transformerSupplier == null;
        this.threadSafeTransformer = new ThreadSafeTransformerWrapper((this.isNoopTransformer) ? NOOP_TRANSFORMER_SUPPLIER : transformerSupplier);
    }

    public Flux<WorkItemCursor> reindex(String indexName, Flux<RfsLuceneDocument> documentStream, IDocumentReindexContext context) {
        // Create executor with hook for threadSafeTransformer cleaner
        int transformationParallelizationFactor = Runtime.getRuntime().availableProcessors();
        var transformScheduler = Schedulers.newBoundedElastic(
            transformationParallelizationFactor,
            Integer.MAX_VALUE,
            r -> new Thread(() -> {
                try {
                    r.run();
                } finally {
                    threadSafeTransformer.close();
                }
            }, "DocumentBulkAggregator"),
            60 // TTL on threads in seconds
        );
        var rfsDocs = documentStream
            .publishOn(Schedulers.parallel())

            // Prep for transform (arbitrary sized) batches
            .map(doc -> RfsDocument.fromLuceneDocument(doc, indexName))
            .buffer(Math.min(100, maxDocsPerBulkRequest))

            // Schedule cleanup for transform threads to occur after use (doFinally started asynchronously from bottom to top)
            .doFinally(signalType -> transformScheduler.dispose())

            // transform docs on transformScheduler thread and maintain order (for correct checkpointing)
            .flatMapSequential(docList ->
                    Flux.defer(() ->
                        Flux.fromIterable(transformDocumentBatch(threadSafeTransformer, docList))
                    ).subscribeOn(transformScheduler),
                transformationParallelizationFactor)
            // Switch off of transformScheduler to limit scope for downstream consumers
            .publishOn(Schedulers.boundedElastic(), 1);
        return this.reindexDocsInParallelBatches(rfsDocs, indexName, context);
    }

    @SneakyThrows
    List<RfsDocument> transformDocumentBatch(IJsonTransformer transformer, List<RfsDocument> docs) {
        if (!isNoopTransformer) {
            return RfsDocument.transform(transformer, docs);
        }
        return docs;
    }

    Flux<WorkItemCursor> reindexDocsInParallelBatches(Flux<RfsDocument> docs, String indexName, IDocumentReindexContext context) {
        // Use parallel scheduler for send subscription due on non-blocking io client
        var scheduler = Schedulers.newParallel("DocumentBatchReindexer");
        var bulkDocsBatches = batchDocsBySizeOrCount(docs);
        var bulkDocsToBuffer = 50; // Arbitrary, takes up 500MB at default settings

        return bulkDocsBatches
            .limitRate(bulkDocsToBuffer, 1) // Bulk Doc Buffer, Keep Full
            // do finally started async bottom to top
            .doFinally(s -> scheduler.dispose())
            .publishOn(scheduler, 1) // Switch scheduler
            .flatMapSequential(
                docsGroup -> sendBulkRequest(UUID.randomUUID(), docsGroup, indexName, context),
                maxConcurrentWorkItems, 1)
            .publishOn(Schedulers.boundedElastic()); // Switch Scheduler afterwards to limit scope of DocumentBatchReindexer

    }


    /*
     * TODO: Update the reindexing code to rely on _index field embedded in each doc section rather than requiring it in the
     * REST path.  See: https://opensearch.atlassian.net/browse/MIGRATIONS-2232
     */
    Mono<WorkItemCursor> sendBulkRequest(UUID batchId, List<RfsDocument> docsBatch, String indexName, IDocumentReindexContext context) {
        var lastDoc = docsBatch.get(docsBatch.size() - 1);
        log.atInfo().setMessage("Last doc is: Source Index " + indexName + " Lucene Doc Number " + lastDoc.progressCheckpointNum).log();

        List<BulkDocSection> bulkDocSections = docsBatch.stream()
                .map(rfsDocument -> rfsDocument.document)
                .collect(Collectors.toList());

        return client.sendBulkRequest(indexName, bulkDocSections, context.createBulkRequest()) // Send the request
            .doFirst(() -> log.atInfo().setMessage("Batch Id:{}, {} documents in current bulk request.")
                .addArgument(batchId)
                .addArgument(docsBatch::size)
                .log())
            .doOnSuccess(unused -> log.atDebug().setMessage("Batch Id:{}, succeeded").addArgument(batchId).log())
            .doOnError(error -> log.atError().setMessage("Batch Id:{}, failed {}")
                .addArgument(batchId)
                .addArgument(error::getMessage)
                .log())
            .map(ignoredResponse -> new WorkItemCursor(lastDoc.progressCheckpointNum));
    }

    Flux<List<RfsDocument>> batchDocsBySizeOrCount(Flux<RfsDocument> docs) {
        return docs.bufferUntil(new Predicate<>() {
            private int currentItemCount = 0;
            private long currentSize = 0;

            @Override
            public boolean test(RfsDocument next) {
                // Add one for newline between bulk sections
                var nextSize = next.document.getSerializedLength() + 1L;
                currentSize += nextSize;
                currentItemCount++;

                if (currentItemCount > maxDocsPerBulkRequest || currentSize > maxBytesPerBulkRequest) {
                    // Reset and return true to signal to stop buffering.
                    // Current item is included in the current buffer
                    currentItemCount = 1;
                    currentSize = nextSize;
                    return true;
                }
                return false;
            }
        }, true);
    }

}
