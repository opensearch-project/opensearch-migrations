package org.opensearch.migrations.parsing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import lombok.ToString;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.StreamReadFeature;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Warmup;

import java.util.Random;
import java.util.ArrayList;
import java.io.IOException;
import java.util.stream.Collectors;
import java.util.UUID;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.MatcherAssert.assertThat;


public class JacksonParserVsReadObjectBenchmark {

    private static final String bulkResponse;
    private static final int expectedSuccesses;
    static {
        var random = new Random(22L);
        var docsCount = 10000;
        var docs = new ArrayList<String>(docsCount);
        var successes = 0;
        for (int i = 0; i < docsCount; i++) {
            var docId = UUID.randomUUID() + "";
            String doc;
            if (random.nextBoolean()) {
                doc = bulkItemResponse(docId, "created");
                successes++;
            } else {
                doc = bulkItemResponseFailure(docId);
            }
            docs.add(doc);
        }
        bulkResponse = bulkItemResponse(true, docs);
        expectedSuccesses = successes;
    }

    private static final ObjectMapper reusedObjectMapper = JsonMapper.builder().build();

    @Test
    @Benchmark
    @Warmup(iterations = 0)
    @Measurement(iterations = 2)
    @BenchmarkMode({Mode.Throughput})
    public void testJacksonMarshalling_reusedObjectMapper() throws IOException {
        var response = reusedObjectMapper.readValue(bulkResponse, Response.class);
        var successfulItems = response.items.stream()
            .filter(i -> i.indexAction.error == null)
            .map(i -> i.indexAction.id)
            .collect(Collectors.toSet());
        assertThat(successfulItems, hasSize(expectedSuccesses));
    }


    @Test
    @Benchmark
    @Warmup(iterations = 0)
    @Measurement(iterations = 2)
    @BenchmarkMode({Mode.Throughput})
    public void testJacksonMarshalling() throws IOException {
        var objectMapper = JsonMapper.builder()
            .enable(StreamReadFeature.IGNORE_UNDEFINED)
            .enable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
            .build();

        var response = objectMapper.readValue(bulkResponse, Response.class);
        var successfulItems = response.items.stream()
            .filter(i -> i.indexAction.error == null)
            .map(i -> i.indexAction.id)
            .collect(Collectors.toSet());
        assertThat(successfulItems, hasSize(expectedSuccesses));
    }

    @Test
    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    @Warmup(iterations = 0)
    @Measurement(iterations = 2)
    public void testJacksonParser() throws IOException {
        var successfulItems = BulkResponseParser.findSuccessDocs(bulkResponse);
        assertThat(successfulItems, hasSize(expectedSuccesses));
    }

    private static String bulkItemResponse(boolean hasErrors, List<String> itemResponses) {
        var sb = new StringBuilder();

        sb.append("{\r\n" + //
            "    \"took\": 11,\r\n" + //
            "    \"errors\": " + hasErrors + ",\r\n" + //
            "    \"items\": [\r\n");

        sb.append(itemResponses.stream().collect(Collectors.joining(",")));
        sb.append("    ]\r\n" + //
            "}");
        return sb.toString();
    }

    private static String bulkItemResponse(String itemId, String result) {
        return ("        {\r\n" + //
            "            \"index\": {\r\n" + //
            "                \"_index\": \"movies\",\r\n" + //
            "                \"_id\": \"{0}\",\r\n" + //
            "                \"_version\": 1,\r\n" + //
            "                \"result\": \"{1}\",\r\n" + //
            "                \"_shards\": {\r\n" + //
            "                    \"total\": 2,\r\n" + //
            "                    \"successful\": 1,\r\n" + //
            "                    \"failed\": 0\r\n" + //
            "                },\r\n" + //
            "                \"_seq_no\": 1,\r\n" + //
            "                \"_primary_term\": 1,\r\n" + //
            "                \"status\": 201\r\n" + //
            "            }\r\n" + //
            "        }\r\n") //
                .replaceAll("\\{0\\}", itemId)
                .replaceAll("\\{1\\}", result);
    }

    private static String bulkItemResponseFailure(String itemId) {
        return ("        {\r\n" + //
            "            \"index\": {\r\n" + //
            "                \"_index\": \"movies\",\r\n" + //
            "                \"_id\": \"{0}\",\r\n" + //
            "                \"status\": 409,\r\n" + //
            "                \"error\": {\r\n" + //
            "                    \"type\": \"version_conflict_engine_exception\",\r\n" + //
            "                    \"reason\": \"[{0}]: version conflict, document already exists (current version [1])\",\r\n" + //
            "                    \"index\": \"movies\",\r\n" + //
            "                    \"shard\": \"0\",\r\n" + //
            "                    \"index_uuid\": \"yhizhusbSWmP0G7OJnmcLg\"\r\n" + //
            "                }\r\n" + //
            "            }\r\n" + //
            "        }\r\n").replaceAll("\\{0\\}", itemId);
    }

    public static class Response {
        public int took;
        public boolean errors;
        public List<Item> items;
    }

    public static class Item {

        @JsonProperty("index")
        private IndexAction indexAction;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IndexAction {
        @JsonProperty("_index")
        private String index;

        @JsonProperty("_id")
        private String id;

        @JsonProperty("_version")
        private int version;

        public String result;

        @JsonProperty("_seq_no")
        private int seqNo;

        @JsonProperty("_primary_term")
        private int primaryTerm;

        public int status;

        public Error error;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Error {
        public String type;
        public String reason;
        public String index;
        public String shard;

        @JsonProperty("index_uuid")
        private String indexUuid;
    }

}
