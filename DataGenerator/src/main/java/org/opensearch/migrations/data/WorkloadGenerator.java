package org.opensearch.migrations.data;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.opensearch.migrations.bulkload.common.BulkDocSection;
import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.data.workloads.Workload;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@AllArgsConstructor
public class WorkloadGenerator {

    private final OpenSearchClient client;

    public void generate(WorkloadOptions options) {
        log.info("Starting document creation");

        // This workload creates ALL documents in memory, schedules them and waits for completion.
        // If larger scale is needed remove the toList() calls and stream all data.  
        var allDocs = new ArrayList<CompletableFuture<?>>();
        for (var workload : options.getWorkloads()) {
            var workloadInstance = workload.getNewInstance().get();
            var docs = workloadInstance
                .indexNames()
                .stream()
                .map(indexName -> generateDocs(indexName, workloadInstance, options))
                .flatMap(List::stream)
                .collect(Collectors.toList());
            allDocs.addAll(docs);
        }

        log.info("All documents queued");
        CompletableFuture.allOf(allDocs.toArray(new CompletableFuture[0])).join();
        log.info("All documents completed");
    }

    private List<CompletableFuture<?>> generateDocs(String indexName, Workload workload, WorkloadOptions options) {
        // This happens inline to be sure the index exists before docs are indexed on it
        var indexRequestDoc = workload.createIndex(options.getIndex().indexSettings.deepCopy());
        log.atInfo().setMessage("Creating index {} with {}").addArgument(indexName).addArgument(indexRequestDoc).log();
        client.createIndex(indexName, indexRequestDoc, null);

        var docIdCounter = new AtomicInteger(0);
        var allDocs = workload.createDocs(options.getTotalDocs())
            .map(doc -> {
                log.atTrace().setMessage("Created doc for index {}: {}")
                    .addArgument(indexName)
                    .addArgument(doc::toString).log();
                var docId = docIdCounter.incrementAndGet();
                var type = options.getDefaultDocType();
                var routing = options.getDefaultDocRouting();
                return new BulkDocSection(indexName + "_" + docId, indexName, type, doc.toString(), routing);
            })
            .collect(Collectors.toList());

        var bulkDocGroups = new ArrayList<List<BulkDocSection>>();
        for (int i = 0; i < allDocs.size(); i += options.getMaxBulkBatchSize()) {
            bulkDocGroups.add(allDocs.subList(i, Math.min(i + options.getMaxBulkBatchSize(), allDocs.size())));
        }

        return bulkDocGroups.stream()
            .map(docs -> {
                var sendFuture = client.sendBulkRequest(indexName, docs, null).toFuture();
                if (options.isRefreshAfterEachWrite()) {
                    sendFuture.thenRun(() -> client.refresh(null));
                    // Requests will be sent in parallel unless we wait for completion
                    // This allows more segments to be created
                    sendFuture.join();
                }
                return sendFuture;
            })
            .collect(Collectors.toList());
    }
}
