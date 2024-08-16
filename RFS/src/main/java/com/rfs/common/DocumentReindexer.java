package com.rfs.common;

import java.util.Collection;
import java.util.function.Predicate;

import org.apache.lucene.document.Document;

import org.opensearch.migrations.reindexer.tracing.IDocumentMigrationContexts;

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

    public Mono<Void> reindex(
        String indexName,
        Flux<Document> documentStream,
        IDocumentMigrationContexts.IDocumentReindexContext context
    ) {
        // Create elastic scheduler for long-lived i/o bound tasks
        Scheduler elasticScheduler = Schedulers.newBoundedElastic(maxConcurrentWorkItems, Integer.MAX_VALUE, "documentReindexerElastic");
        // Create scheduler for short-lived CPU bound tasks
        Scheduler genericScheduler = Schedulers.newParallel( "documentReindexer");

        return documentStream
            .publishOn(genericScheduler)
            .map(BulkDocSection::new)
            .bufferUntil(new Predicate<>() {
                private int currentItemCount = 0;
                private long currentSize = 0;

                @Override
                public boolean test(BulkDocSection next) {
                    // TODO: Move to Bytebufs to convert from string to bytes only once
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
            }, true)
             .parallel(
                maxConcurrentWorkItems, // Number of parallel workers, tested in reindex_shouldRespectMaxConcurrentRequests
                maxConcurrentWorkItems // Limit prefetch for memory pressure
            )
            .runOn(elasticScheduler, 1) // Use elasticScheduler for I/O bound request sending
            .flatMap(
                bulkDocs -> client
                    .sendBulkRequest(indexName, bulkDocs, context.createBulkRequest()) // Send the request
                    .doFirst(() -> log.atInfo().log("{} documents in current bulk request.", bulkDocs.size()))
                    .doOnSuccess(unused -> log.atDebug().log("Batch succeeded"))
                    .doOnError(error -> log.atError().log("Batch failed {}", error))
                    // Prevent the error from stopping the entire stream, retries occurring within sendBulkRequest
                    .onErrorResume(e -> Mono.empty()),
            false,
                1, // control concurrency on parallel rails
                1 // control prefetch across all parallel runners
            )
            .doOnComplete(() -> log.debug("All batches processed"))
            .then()
            .publishOn(Schedulers.single()) // replace with Standard scheduler before disposing schedulers
            .doFinally(unused -> {
                elasticScheduler.dispose();
                genericScheduler.dispose();
            });
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

        public String asBulkIndex() {
            return this.bulkIndex;
        }

        public static String convertToBulkRequestBody(Collection<BulkDocSection> bulkSections) {
            StringBuilder builder = new StringBuilder();
            for (var section : bulkSections) {
                var indexCommand = section.asBulkIndex();
                builder.append(indexCommand).append("\n");
            }
            return builder.toString();
        }

    }
}
