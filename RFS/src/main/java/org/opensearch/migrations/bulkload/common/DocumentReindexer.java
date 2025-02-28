package org.opensearch.migrations.bulkload.common;

import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.opensearch.migrations.bulkload.worker.WorkItemCursor;
import org.opensearch.migrations.reindexer.tracing.IDocumentMigrationContexts.IDocumentReindexContext;
import org.opensearch.migrations.transform.IJsonTransformer;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

@Slf4j
@RequiredArgsConstructor
public class DocumentReindexer {

    protected final OpenSearchClient client;
    private final int maxDocsPerBulkRequest;
    private final long maxBytesPerBulkRequest;
    private final int maxConcurrentWorkItems;
    private final Supplier<IJsonTransformer> transformerSupplier;

    public Flux<WorkItemCursor> reindex(String indexName, Flux<RfsLuceneDocument> documentStream, IDocumentReindexContext context) {
        // Transformers cannot be used simultaneously
        var threadSafeTransformer = ThreadLocal.withInitial(transformerSupplier);
        var scheduler = Schedulers.newParallel("DocumentBulkAggregator");
        var rfsDocs = documentStream
            .publishOn(scheduler, 1)
            .concatMapIterable(doc -> transformDocument(threadSafeTransformer, doc, indexName));

        return this.reindexDocsInParallelBatches(rfsDocs, indexName, context)
            .doFinally(s -> scheduler.dispose());
    }

    Flux<WorkItemCursor> reindexDocsInParallelBatches(Flux<RfsDocument> docs, String indexName, IDocumentReindexContext context) {
        // Use parallel scheduler for send subscription due on non-blocking io client
        var scheduler = Schedulers.newParallel("DocumentBatchReindexer");
        var bulkDocsBatches = batchDocsBySizeOrCount(docs);
        var bulkDocsToBuffer = 50; // Arbitrary, takes up 500MB at default settings

        return bulkDocsBatches
            .limitRate(bulkDocsToBuffer, 1) // Bulk Doc Buffer, Keep Full
            .publishOn(scheduler, 1) // Switch scheduler
            .flatMapSequential(docsGroup -> sendBulkRequest(UUID.randomUUID(), docsGroup, indexName, context, scheduler),
                maxConcurrentWorkItems)
            .doFinally(s -> scheduler.dispose());
    }

    @SneakyThrows
    List<RfsDocument> transformDocument(ThreadLocal<IJsonTransformer> transformerLocal, RfsLuceneDocument doc, String indexName) {
        var transformer = transformerLocal.get();
        var originalDoc = RfsDocument.fromLuceneDocument(doc, indexName);
        if (transformer != null) {
            return RfsDocument.transform(transformer, originalDoc);
        }
        return List.of(originalDoc);
    }

    /*
     * TODO: Update the reindexing code to rely on _index field embedded in each doc section rather than requiring it in the
     * REST path.  See: https://opensearch.atlassian.net/browse/MIGRATIONS-2232
     */
    Mono<WorkItemCursor> sendBulkRequest(UUID batchId, List<RfsDocument> docsBatch, String indexName, IDocumentReindexContext context, Scheduler scheduler) {
        var lastDoc = docsBatch.get(docsBatch.size() - 1);
        log.atInfo().setMessage("Last doc is: Source Index " + indexName + " Lucene Doc Number " + lastDoc.luceneDocNumber).log();

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
            // Prevent the error from stopping the entire stream, retries occurring within sendBulkRequest
            .onErrorResume(e -> Mono.empty())
            .then(Mono.just(new WorkItemCursor(lastDoc.luceneDocNumber))
            .subscribeOn(scheduler));
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
