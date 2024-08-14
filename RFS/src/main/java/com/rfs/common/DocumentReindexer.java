package com.rfs.common;

import java.util.Collection;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.lucene.document.Document;

import org.opensearch.migrations.reindexer.tracing.IDocumentMigrationContexts;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
@RequiredArgsConstructor
public class DocumentReindexer {
    private static final ObjectMapper objectMapper = new ObjectMapper();
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
            .doOnNext(bulk -> log.atInfo().log("{} documents in current bulk request", bulk.size()))
            .flatMap(
                bulkDocs -> client.sendBulkRequest(indexName, bulkDocs, context.createBulkRequest()) // Send the request
                    .doOnSuccess(unused -> log.atDebug().log("Batch succeeded"))
                    .doOnError(error -> log.atError().log("Batch failed", error))
                    // Prevent the error from stopping the entire stream, retries occurring within sendBulkRequest
                    .onErrorResume(e -> Mono.empty()),
                maxConcurrentRequests
            )
            .doOnComplete(() -> log.atDebug().log("All batches processed"))
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

        @SneakyThrows
        public String asBulkIndex() {
            String action = "{\"index\": {\"_id\": \"" + getDocId() + "\"}}";
            // We must ensure the _source document is a "minified" JSON string, otherwise the bulk request will be corrupted.
            // Specifically, we cannot have any leading or trailing whitespace, and the JSON must be on a single line.
            String trimmedSource = doc.getBinaryValue("_source").utf8ToString().trim();
            Object jsonObject = objectMapper.readValue(trimmedSource, Object.class);
            String minifiedSource = objectMapper.writeValueAsString(jsonObject);
            return action + "\n" + minifiedSource;
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
