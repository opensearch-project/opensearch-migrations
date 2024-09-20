package org.opensearch.migrations.data;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.http.ClusterOperations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class GeneratedData {

    private static final ObjectMapper mapper = new ObjectMapper();

    static ObjectNode createField(String type) {
        var field = mapper.createObjectNode();
        field.put("type", type);
        return field;
    }

    /** Replacement for OSB startup script */
    public static void createDefaultTestData(ClusterOperations operations) {
        var httpLogIndexSetting = HttpLogs.generateHttpLogIndex();
        var httpLogIndexName = "logs-221998";
        operations.createIndex(httpLogIndexName, httpLogIndexSetting.toString());

        // TODO: Missing several logs indexes
        var httpDocs = generateDocs(operations, "logs-221998", HttpLogs::generateHttpLogIndex, HttpLogs::generateHttpLogDocs);
        var geonameDocs = generateDocs(operations, "geonames", Geonames::generateGeonameIndex, Geonames::generateGeoNameDocs);
        var nestedDocs = generateDocs(operations, "sonested", Nested::generateNestedIndex, Nested::generateNestedDocs);
        var nycTaxisDocs = generateDocs(operations, "nyc_taxis", NycTaxis::generateNycTaxisIndex, NycTaxis::generateNycTaxiDocs);


        var allDocs = new ArrayList<CompletableFuture<Void>>();
        allDocs.addAll(httpDocs);
        allDocs.addAll(geonameDocs);
        allDocs.addAll(nestedDocs);
        allDocs.addAll(nycTaxisDocs);
        
        CompletableFuture.allOf(allDocs.toArray(new CompletableFuture[0])).join();
    }

    private static List<CompletableFuture<Void>> generateDocs(
        ClusterOperations operations,
        String indexName,
        Supplier<ObjectNode> indexSettings,
        IntFunction<Stream<ObjectNode>> docGenerator) {
        var indexSetting = indexSettings.get();
        // This happens inline to be sure the index exists before docs are indexed on it
        operations.createIndex(indexName, indexSetting.toString());
        var docIdCounter = new AtomicInteger(0);

        return docGenerator.apply(1000)
            .map(doc -> CompletableFuture.runAsync(() ->
                    // If these were done in batches it would likely be much faster
                    operations.createDocument(
                        indexName,
                        "doc" + docIdCounter.getAndIncrement(),
                        doc.toString()
                    )
                )
            )
            .collect(Collectors.toList());
    }

    @FunctionalInterface
    interface DocumentGenerator {
        void createDoc(String indexName, String docName, ObjectNode docBody);
    }

}
