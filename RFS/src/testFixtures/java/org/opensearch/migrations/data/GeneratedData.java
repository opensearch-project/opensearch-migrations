package org.opensearch.migrations.data;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.common.DocumentReindexer;
import org.opensearch.migrations.bulkload.common.OpenSearchClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GeneratedData {

    private static final ObjectMapper mapper = new ObjectMapper();

    static ObjectNode createField(String type) {
        var field = mapper.createObjectNode();
        field.put("type", type);
        return field;
    }

    static ObjectNode createFieldTextRawKeyword() {
        var fieldNode = mapper.createObjectNode();
        fieldNode.put("type", "text");
        var fieldsNode = mapper.createObjectNode();
        fieldsNode.set("raw", createField("keyword"));
        fieldNode.set("fields", fieldsNode);
        return fieldNode;
    }

    /** Replacement for OSB startup script */
    public static void createDefaultTestData(OpenSearchClient client) {
        log.info("Starting document creation");
        var httpIndexes = List.of(
            "logs-221998",
            "logs-211998",
            "logs-231998",
            "logs-241998",
            "logs-181998",
            "logs-201998",
            "logs-191998"
        );
        var httpDocs = httpIndexes
            .stream()
            .map(httpIndex -> generateDocs(client, httpIndex, HttpLogs::generateHttpLogIndex, HttpLogs::generateHttpLogDocs))
            .flatMap(List::stream)
            .collect(Collectors.toList());
        var geonameDocs = generateDocs(client, "geonames", Geonames::generateGeonameIndex, Geonames::generateGeoNameDocs);
        var nestedDocs = generateDocs(client, "sonested", Nested::generateNestedIndex, Nested::generateNestedDocs);
        var nycTaxisDocs = generateDocs(client, "nyc_taxis", NycTaxis::generateNycTaxisIndex, NycTaxis::generateNycTaxiDocs);


        var allDocs = new ArrayList<CompletableFuture<?>>();
        allDocs.addAll(httpDocs);
        allDocs.addAll(geonameDocs);
        allDocs.addAll(nestedDocs);
        allDocs.addAll(nycTaxisDocs);
        
        log.info("All document queued");
        CompletableFuture.allOf(allDocs.toArray(new CompletableFuture[0])).join();
        log.info("All document completed");
    }

    private static List<CompletableFuture<?>> generateDocs(
        OpenSearchClient client,
        String indexName,
        Supplier<ObjectNode> indexSettings,
        IntFunction<Stream<ObjectNode>> docGenerator) {
        // This happens inline to be sure the index exists before docs are indexed on it
        client.createIndex(indexName, indexSettings.get(), null);
        int totalDocs = 1000;

        var docIdCounter = new AtomicInteger(0);
        var allDocs = docGenerator.apply(totalDocs)
            .map(doc -> new DocumentReindexer.BulkDocSection(
                docIdCounter.incrementAndGet() + "", doc.toString()))
            .collect(Collectors.toList());

        int maxBulkDocs = 50;
        var bulkDocGroups = new ArrayList<List<DocumentReindexer.BulkDocSection>>();
        for (int i = 0; i < allDocs.size(); i += maxBulkDocs) {
            bulkDocGroups.add(allDocs.subList(i, Math.min(i + maxBulkDocs, allDocs.size())));
        }

        return bulkDocGroups.stream()
            .map(docs -> client.sendBulkRequest(indexName, docs, null).toFuture())
            .collect(Collectors.toList());
    }

    @FunctionalInterface
    interface DocumentGenerator {
        void createDoc(String indexName, String docName, ObjectNode docBody);
    }

}
