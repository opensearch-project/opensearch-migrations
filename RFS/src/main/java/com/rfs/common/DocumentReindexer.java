package com.rfs.common;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import org.apache.lucene.document.Document;

import org.opensearch.migrations.reindexer.tracing.IDocumentMigrationContexts.IDocumentReindexContext;

import lombok.EqualsAndHashCode;
import lombok.Getter;
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

    public Mono<Void> reindex(String indexName, Flux<Document> documentStream, IDocumentReindexContext context) {
        var scheduler = Schedulers.newParallel("DocumentBulkAggregator");
        var bulkDocs = documentStream
            .publishOn(scheduler, 1)
            .map(BulkDocSection::new);

        return this.reindexDocsInParallelBatches(bulkDocs, indexName, context)
            .doOnSuccess(unused -> log.debug("All batches processed"))
            .doOnError(e -> log.error("Error prevented all batches from being processed", e))
            .doOnTerminate(scheduler::dispose);
    }

    Mono<Void> reindexDocsInParallelBatches(Flux<BulkDocSection> docs, String indexName, IDocumentReindexContext context) {
        // Use parallel scheduler for send subscription due on non-blocking io client
        var scheduler = Schedulers.newParallel("DocumentBatchReindexer");
        var bulkDocsBatches = batchDocsBySizeOrCount(docs);
        var bulkDocsToBuffer = 50; // Arbitrary, takes up 500MB at default settings

        return bulkDocsBatches
            .limitRate(bulkDocsToBuffer, 1) // Bulk Doc Buffer, Keep Full
            .publishOn(scheduler, 1) // Switch scheduler
            .flatMap(docsGroup -> sendBulkRequest(UUID.randomUUID(), docsGroup, indexName, context, scheduler),
                maxConcurrentWorkItems)
            .doOnTerminate(scheduler::dispose)
            .then();
    }

    Mono<Void> sendBulkRequest(UUID batchId, List<BulkDocSection> docsBatch, String indexName, IDocumentReindexContext context, Scheduler scheduler) {
        return client.sendBulkRequest(indexName, docsBatch, context.createBulkRequest()) // Send the request
            .doFirst(() -> log.atInfo().log("Batch Id:{}, {} documents in current bulk request.", batchId, docsBatch.size()))
            .doOnSuccess(unused -> log.atDebug().log("Batch Id:{}, succeeded", batchId))
            .doOnError(error -> log.atError().log("Batch Id:{}, failed {}", batchId, error.getMessage()))
            // Prevent the error from stopping the entire stream, retries occurring within sendBulkRequest
            .onErrorResume(e -> Mono.empty())
            .then() // Discard the response object
            .subscribeOn(scheduler);
    }

    Flux<List<BulkDocSection>> batchDocsBySizeOrCount(Flux<BulkDocSection> docs) {
        return docs.bufferUntil(new Predicate<>() {
            private int currentItemCount = 0;
            private long currentSize = 0;

            @Override
            public boolean test(BulkDocSection next) {
                // Add one for newline between bulk sections
                var nextSize = next.asBulkIndex().length() + 1L;
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

    @EqualsAndHashCode(onlyExplicitlyIncluded = true)
    public static class BulkDocSection {

        @EqualsAndHashCode.Include
        @Getter
        private final String docId;
        private final String bulkIndex;

        public BulkDocSection(Document doc) {
            this.docId = Uid.decodeId(doc.getBinaryValue("_id").bytes);
            this.bulkIndex = createBulkIndex(docId, doc);
        }

        @SneakyThrows
        private static String createBulkIndex(final String docId, final Document doc) {
            // For a successful bulk ingestion, we cannot have any leading or trailing whitespace, and  must be on a single line.
            String trimmedSource = doc.getBinaryValue("_source").utf8ToString().trim().replace("\n", "");
            return "{\"index\":{\"_id\":\"" + docId + "\"}}" + "\n" + trimmedSource;
        }

        public static String convertToBulkRequestBody(Collection<BulkDocSection> bulkSections) {
            StringBuilder builder = new StringBuilder();
            for (var section : bulkSections) {
                var indexCommand = section.asBulkIndex();
                builder.append(indexCommand).append("\n");
            }
            return builder.toString();
        }

        public String asBulkIndex() {
            return this.bulkIndex;
        }
    }
}
