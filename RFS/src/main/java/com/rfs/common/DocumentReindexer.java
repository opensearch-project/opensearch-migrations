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
import reactor.core.scheduler.Schedulers;

@Slf4j
@RequiredArgsConstructor
public class DocumentReindexer {

  protected final OpenSearchClient client;
  private final int maxDocsPerBulkRequest;
  private final long maxBytesPerBulkRequest;
  private final int maxConcurrentWorkItems;

  public Mono<Void> reindex(String indexName, Flux<Document> documentStream, IDocumentMigrationContexts.IDocumentReindexContext context) {
    // Create scheduler for short-lived CPU bound tasks
    var genericScheduler = Schedulers.newParallel("documentReindexer");
    // Create elastic scheduler for long-lived i/o bound tasks
    var elasticScheduler = Schedulers.newBoundedElastic(maxConcurrentWorkItems, Integer.MAX_VALUE, "documentReindexerElastic");

    return Flux.using(() -> documentStream, docs ->
            docs.publishOn(genericScheduler)
                .map(BulkDocSection::new)
                .bufferUntil(new Predicate<>() { // Group BulkDocSections up to smaller of maxDocsPerBulkRequest and maxBytesPerBulkRequest
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
                .parallel(maxConcurrentWorkItems, // Number of parallel workers, tested in reindex_shouldRespectMaxConcurrentRequests
                    maxConcurrentWorkItems // Limit prefetch for memory pressure
                ).runOn(elasticScheduler, 1) // Use elasticScheduler for I/O bound request sending
                .concatMapDelayError( // Delay errors to attempt putting all documents before exiting
                    bulkDocs -> client.sendBulkRequest(indexName, bulkDocs, context.createBulkRequest()) // Send the request
                        .publishOn(elasticScheduler) // Continue to use same elasticScheduler
                        .doFirst(() -> log.atInfo().log("{} documents in current bulk request.", bulkDocs.size()))
                        .doOnSuccess(unused -> log.atDebug().log("Batch succeeded"))
                        .doOnError(error -> log.atError().log("Batch failed {}", error))
                        // Prevent the error from stopping the entire stream, retries occurring within sendBulkRequest
                        .onErrorResume(e -> Mono.empty())
                ), unused -> {
          // Cleanup Schedulers
          elasticScheduler.dispose();
          genericScheduler.dispose();
        }).then()
        .doOnSuccess(unused -> log.debug("All batches processed"))
        .doOnError(e -> log.error("Error prevented all batches from being processed", e));
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
