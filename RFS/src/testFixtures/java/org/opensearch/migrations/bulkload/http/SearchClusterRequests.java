package org.opensearch.migrations.bulkload.http;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.opensearch.migrations.bulkload.common.RestClient;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

public class SearchClusterRequests {

    private final ObjectMapper mapper = new ObjectMapper();
    private final DocumentMigrationTestContext context;

    public SearchClusterRequests(DocumentMigrationTestContext context) {
        this.context = context;
    }

    @SneakyThrows
    public Map<String, Integer> getMapOfIndexAndDocCount(final RestClient client) {
        var catIndicesResponse = client.get("_cat/indices?format=json", context.createUnboundRequestContext());
        assertThat(catIndicesResponse.statusCode, equalTo(200));

        var catBodyJson = mapper.readTree(catIndicesResponse.body);
        var allIndices = new ArrayList<String>();
        catBodyJson.forEach(index -> allIndices.add(index.get("index").asText()));

        var interestingIndices = filterToInterestingIndices(allIndices);

        /**
         * Why not trust the doc.count from `_cat/indices?
         * Turns out that count can include deleted/updated documents too depending on the search cluster implementation
         * by querying count directly on each index it ensures the number of documents no matter if this bug exists or not
         *
         * See https://github.com/elastic/elasticsearch/issues/25868#issuecomment-317990140
         */
        var mapOfIndexAndDocCount = interestingIndices.stream().collect(Collectors.toMap(i -> i, i -> {
            try {
                var response = client.get(i + "/_count", context.createUnboundRequestContext());
                var countFromResponse = mapper.readTree(response.body).get("count").asInt();
                return countFromResponse;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }));

        return mapOfIndexAndDocCount;
    }

    @SneakyThrows
    public JsonNode searchIndexByQueryString(
        final RestClient client,
        final String index,
        final String queryString,
        final String routing) {
        var path = index + "/_search?q=" + queryString;
        if (routing != null) {
            path = path + "&routing=" + routing;
        }

        var searchResponse = client.get(path, context.createUnboundRequestContext());
        assertThat(
                String.format(
                        "Expected 200 but got %d. Path: %s, Body: %s",
                        searchResponse.statusCode, path, searchResponse.body),
                searchResponse.statusCode,
                equalTo(200));

        return mapper.readTree(searchResponse.body).get("hits").get("hits");
    }

    public List<String> filterToInterestingIndices(final List<String> indices) {
        return indices.stream()
            .filter(index -> !index.startsWith("."))
            .filter(index -> !index.startsWith("reindexed-logs"))
            .collect(Collectors.toList());
    }
}
