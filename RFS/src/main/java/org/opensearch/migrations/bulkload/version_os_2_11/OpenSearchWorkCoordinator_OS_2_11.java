package org.opensearch.migrations.bulkload.version_os_2_11;

import java.time.Clock;
import java.util.function.Consumer;

import org.opensearch.migrations.bulkload.workcoordination.AbstractedHttpClient;
import org.opensearch.migrations.bulkload.workcoordination.OpenSearchWorkCoordinator;

import com.fasterxml.jackson.databind.JsonNode;

public class OpenSearchWorkCoordinator_OS_2_11 extends OpenSearchWorkCoordinator {    
        public OpenSearchWorkCoordinator_OS_2_11(
            AbstractedHttpClient httpClient,
            long tolerableClientServerClockDifferenceSeconds,
            String workerId
        ) {
            super(httpClient, tolerableClientServerClockDifferenceSeconds, workerId);
        }

        public OpenSearchWorkCoordinator_OS_2_11(
            AbstractedHttpClient httpClient,
            long tolerableClientServerClockDifferenceSeconds,
            String workerId,
            Clock clock,
            Consumer<WorkItemAndDuration> workItemConsumer
        ) {
            super(httpClient, tolerableClientServerClockDifferenceSeconds, workerId, clock, workItemConsumer);
        }

        protected String getCoordinationIndexSettingsBody(){
            return "{\n"
            + "  \"settings\": {\n"
            + "   \"index\": {"
            + "    \"number_of_shards\": 1,\n"
            + "    \"number_of_replicas\": 1,\n"
            + "    \"refresh_interval\": \"30s\"\n"
            + "   }\n"
            + "  },\n"
            + "  \"mappings\": {\n"
            + "    \"properties\": {\n"
            + "      \"" + EXPIRATION_FIELD_NAME + "\": {\n"
            + "        \"type\": \"long\"\n"
            + "      },\n"
            + "      \"" + COMPLETED_AT_FIELD_NAME + "\": {\n"
            + "        \"type\": \"long\"\n"
            + "      },\n"
            + "      \"leaseHolderId\": {\n"
            + "        \"type\": \"keyword\",\n"
            + "        \"norms\": false\n"
            + "      },\n"
            + "      \"status\": {\n"
            + "        \"type\": \"keyword\",\n"
            + "        \"norms\": false\n"
            + "      },\n"
            + "     \"" + SUCCESSOR_ITEMS_FIELD_NAME + "\": {\n"
            + "       \"type\": \"keyword\",\n"
            + "        \"norms\": false\n"
            + "      }\n"
            + "    }\n"
            + "  }\n"
            + "}\n";
        }

        protected String getPathForUpdates(String workItemId) {
            return INDEX_NAME + "/_update/" + workItemId + "?refresh=true";
        }

        protected String getPathForBulkUpdates() {
            return INDEX_NAME + "/_bulk?refresh=true";
        }

        protected String getPathForSingleDocumentUpdateByQuery() { return INDEX_NAME + "/_update_by_query?refresh=true&max_docs=1"; }

        protected String getPathForGets(String workItemId) {
            return INDEX_NAME + "/_doc/" + workItemId;
        }

        protected String getPathForSearches() {
            return INDEX_NAME + "/_search";
        }

        protected int getTotalHitsFromSearchResponse(JsonNode searchResponse) {
            return searchResponse.path("hits").path("total").path("value").asInt();
        }


}
