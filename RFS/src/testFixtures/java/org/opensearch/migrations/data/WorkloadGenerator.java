package org.opensearch.migrations.data;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.opensearch.migrations.bulkload.common.DocumentReindexer;
import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.data.workloads.Workload;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class WorkloadGenerator {

    /** Replacement for OSB startup script */
    public static void createDefaultTestData(OpenSearchClient client) {
        createDefaultTestData(client, new WorkloadOptions());
    }

    public static void createDefaultTestData(OpenSearchClient client, WorkloadOptions options) {
        log.info("Starting document creation");

        var allDocs = new ArrayList<CompletableFuture<?>>();
        for (var workload : options.workloads) {
            var docs = workload
                .indexNames()
                .stream()
                .map(indexName -> generateDocs(client, indexName, workload, options))
                .flatMap(List::stream)
                .collect(Collectors.toList());
            allDocs.addAll(docs);
        }

        log.info("All document queued");
        CompletableFuture.allOf(allDocs.toArray(new CompletableFuture[0])).join();
        log.info("All document completed");
    }

    private static List<CompletableFuture<?>> generateDocs(OpenSearchClient client, String indexName, Workload workload, WorkloadOptions options) {
        // This happens inline to be sure the index exists before docs are indexed on it
        client.createIndex(indexName, workload.createIndex(options.index.indexSettings), null);

        var docIdCounter = new AtomicInteger(0);
        var allDocs = workload.createDocs(options.totalDocs)
            .map(doc -> new DocumentReindexer.BulkDocSection(docIdCounter.incrementAndGet() + "", doc.toString()))
            .collect(Collectors.toList());

        var bulkDocGroups = new ArrayList<List<DocumentReindexer.BulkDocSection>>();
        for (int i = 0; i < allDocs.size(); i += options.maxBulkBatchSize) {
            bulkDocGroups.add(allDocs.subList(i, Math.min(i + options.maxBulkBatchSize, allDocs.size())));
        }

        return bulkDocGroups.stream()
            .map(docs -> client.sendBulkRequest(indexName, docs, null).toFuture())
            .collect(Collectors.toList());
    }
}
