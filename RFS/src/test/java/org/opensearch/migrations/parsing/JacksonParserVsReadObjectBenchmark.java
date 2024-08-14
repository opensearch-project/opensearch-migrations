package org.opensearch.migrations.parsing;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import com.rfs.http.BulkRequestGenerator;
import com.rfs.http.BulkRequestGenerator.BulkItemResponseEntry;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Warmup;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;

public class JacksonParserVsReadObjectBenchmark {

    private static final String bulkResponse;
    private static final int expectedSuccesses;
    static {
        var random = new Random(22L);
        var docsCount = 1000;
        var docs = new ArrayList<BulkItemResponseEntry>(docsCount);
        var successes = 0;
        for (int i = 0; i < docsCount; i++) {
            var docId = UUID.randomUUID() + "";
            BulkItemResponseEntry doc;
            if (random.nextBoolean()) {
                doc = BulkRequestGenerator.itemEntry(docId);
                successes++;
            } else {
                doc = BulkRequestGenerator.itemEntryFailure(docId);
            }
            docs.add(doc);
        }
        bulkResponse = BulkRequestGenerator.bulkItemResponse(true, docs);
        expectedSuccesses = successes;
    }

    @Test
    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    @Warmup(iterations = 0)
    @Measurement(iterations = 2)
    public void testJacksonMarshalling() throws IOException {
        var objectMapper = JsonMapper.builder()
            .enable(StreamReadFeature.IGNORE_UNDEFINED)
            .enable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
            .build();

        var response = objectMapper.readValue(bulkResponse, BulkResponse.class);
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

    public static class BulkResponse {
        public int took;
        public boolean errors;
        public List<Item> items;
    }

    public static class Item {
        @JsonProperty("index")
        public IndexAction indexAction;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IndexAction {
        @JsonProperty("_index")
        public String index;
        @JsonProperty("_id")
        public String id;
        @JsonProperty("_version")
        public int version;
        public String result;
        @JsonProperty("_seq_no")
        public int seqNo;
        @JsonProperty("_primary_term")
        public int primaryTerm;
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
