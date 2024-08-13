package com.rfs.common;

import java.util.Collection;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.document.Document;

import org.opensearch.migrations.reindexer.tracing.IDocumentMigrationContexts;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RequiredArgsConstructor
public class DocumentReindexer {
    private static final Logger logger = LogManager.getLogger(DocumentReindexer.class);
    protected final OpenSearchClient client;
    private final int numDocsPerBulkRequest;
    private final int maxConcurrentRequests;

    public Mono<Void> reindex(
        String indexName,
        Flux<Document> documentStream,
        IDocumentMigrationContexts.IDocumentReindexContext context
    ) {

        return documentStream.map(BulkDocSection::new)
            .buffer(numDocsPerBulkRequest) // Collect until you hit the batch size
            .doOnNext(bulk -> logger.info("{} documents in current bulk request", bulk.size()))
            .flatMap(
                bulkDocs -> client.sendBulkRequest(indexName, bulkDocs, context.createBulkRequest()) // Send the request
                    .doOnSuccess(unused -> logger.debug("Batch succeeded"))
                    .doOnError(error -> logger.error("Batch failed", error))
                    // Prevent the error from stopping the entire stream, retries occurring within sendBulkRequest
                    .onErrorResume(e -> Mono.empty()),
                maxConcurrentRequests
            )
            .doOnComplete(() -> logger.debug("All batches processed"))
            .then();
    }

    @EqualsAndHashCode(onlyExplicitlyIncluded = true)
    public static class BulkDocSection {
        private Document doc;
        @EqualsAndHashCode.Include
        @Getter
        private String docId;

        public BulkDocSection(Document doc) {
            this.doc = doc;
            this.docId = Uid.decodeId(doc.getBinaryValue("_id").bytes);
        }

        String asBulkIndex() {
            String source = doc.getBinaryValue("_source").utf8ToString();
            String action = "{\"index\": {\"_id\": \"" + getDocId() + "\"}}";
            return action + "\n" + source;
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
