package org.opensearch.migrations.bulkload.common;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import org.opensearch.migrations.reindexer.tracing.IDocumentMigrationContexts.IDocumentReindexContext;
import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.bulkload.worker.IndexAndShardCursor;

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
    private final IJsonTransformer transformer;

    public Flux<IndexAndShardCursor> reindex(String indexName, int shardNumber, Flux<RfsLuceneDocument> documentStream, IDocumentReindexContext context) {
        var scheduler = Schedulers.newParallel("DocumentBulkAggregator");
        var bulkDocs = documentStream
            .publishOn(scheduler, 1)
            .map(doc -> transformDocument(doc, indexName));

        return this.reindexDocsInParallelBatches(bulkDocs, indexName, shardNumber, context)
            .doOnTerminate(scheduler::dispose);
    }

    Flux<IndexAndShardCursor> reindexDocsInParallelBatches(Flux<BulkDocSection> docs, String indexName, int shardNumber, IDocumentReindexContext context) {
        // Use parallel scheduler for send subscription due on non-blocking io client
        var scheduler = Schedulers.newParallel("DocumentBatchReindexer");
        var bulkDocsBatches = batchDocsBySizeOrCount(docs);
        var bulkDocsToBuffer = 50; // Arbitrary, takes up 500MB at default settings

        return bulkDocsBatches
            .limitRate(bulkDocsToBuffer, 1) // Bulk Doc Buffer, Keep Full
            .publishOn(scheduler, 1) // Switch scheduler
            .flatMapSequential(docsGroup -> sendBulkRequest(UUID.randomUUID(), docsGroup, indexName, shardNumber, context, scheduler),
                maxConcurrentWorkItems)
            .doOnTerminate(scheduler::dispose);
    }

    @SneakyThrows
    BulkDocSection transformDocument(RfsLuceneDocument doc, String indexName) {
        log.atInfo().setMessage("Transforming luceneSegId {}, luceneDocId {}, osDocId {}")
            .addArgument(doc.luceneSegId)
            .addArgument(doc.luceneDocId)
            .addArgument(doc.osDocId)
            .log();
        var original = new BulkDocSection(doc.luceneSegId, doc.luceneDocId, doc.osDocId, indexName, doc.type, doc.source);
        if (transformer != null) {
            final Map<String,Object> transformedDoc = transformer.transformJson(original.toMap());
            return BulkDocSection.fromMap(transformedDoc);
        }
        return BulkDocSection.fromMap(original.toMap());
    }

    Mono<IndexAndShardCursor> sendBulkRequest(UUID batchId, List<BulkDocSection> docsBatch, String indexName, int shardNumber, IDocumentReindexContext context, Scheduler scheduler) {
        var lastDoc = docsBatch.get(docsBatch.size() - 1);

        return client.sendBulkRequest(indexName, docsBatch, context.createBulkRequest()) // Send the request
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
            .then(Mono.just(new IndexAndShardCursor(indexName, shardNumber, lastDoc.getLuceneSegId(), lastDoc.getLuceneDocId()))
            .subscribeOn(scheduler));
    }

    Flux<List<BulkDocSection>> batchDocsBySizeOrCount(Flux<BulkDocSection> docs) {
        return docs.bufferUntil(new Predicate<>() {
            private int currentItemCount = 0;
            private long currentSize = 0;

            @Override
            public boolean test(BulkDocSection next) {
                // Add one for newline between bulk sections
                var nextSize = next.getSerializedLength() + 1L;
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
