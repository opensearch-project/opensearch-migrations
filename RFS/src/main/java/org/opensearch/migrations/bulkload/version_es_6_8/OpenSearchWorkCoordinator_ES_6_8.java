package org.opensearch.migrations.bulkload.version_es_6_8;
import java.time.Clock;
import java.util.function.Consumer;

import org.opensearch.migrations.bulkload.workcoordination.AbstractedHttpClient;
import org.opensearch.migrations.bulkload.workcoordination.OpenSearchWorkCoordinator;

import com.fasterxml.jackson.databind.JsonNode;

public class OpenSearchWorkCoordinator_ES_6_8 extends OpenSearchWorkCoordinator {    
        public OpenSearchWorkCoordinator_ES_6_8(
            AbstractedHttpClient httpClient,
            long tolerableClientServerClockDifferenceSeconds,
            String workerId
        ) {
            super(httpClient, tolerableClientServerClockDifferenceSeconds, workerId);
        }

        public OpenSearchWorkCoordinator_ES_6_8(
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
            + "    \"doc\": {\n"
            + "      \"properties\": {\n"
            + "        \"" + EXPIRATION_FIELD_NAME + "\": {\n"
            + "          \"type\": \"long\"\n"
            + "        },\n"
            + "        \"" + COMPLETED_AT_FIELD_NAME + "\": {\n"
            + "          \"type\": \"long\"\n"
            + "        },\n"
            + "        \"leaseHolderId\": {\n"
            + "          \"type\": \"keyword\",\n"
            + "          \"norms\": false\n"
            + "        },\n"
            + "        \"status\": {\n"
            + "          \"type\": \"keyword\",\n"
            + "        \"norms\": false\n"
            + "        },\n"
            + "       \"" + SUCCESSOR_ITEMS_FIELD_NAME + "\": {\n"
            + "         \"type\": \"keyword\",\n"
            + "          \"norms\": false\n"
            + "        }\n"
            + "      }\n"
            + "    }\n"
            + "  }\n"
            + "}\n";
        }
        protected String getPathForUpdates(String workItemId) {
            return INDEX_NAME + "/doc/" + workItemId + "/_update?refresh=true";
        }

        protected String getPathForBulkUpdates() {
            return INDEX_NAME + "/doc/_bulk?refresh=true";
        }

        protected String getPathForSingleDocumentUpdateByQuery() { return INDEX_NAME + "/_update_by_query?refresh=true&size=1"; }

        protected String getPathForGets(String workItemId) {
            return INDEX_NAME + "/doc/" + workItemId;
        }

        protected String getPathForSearches() {
            return INDEX_NAME + "/doc/_search";
        }

        protected int getTotalHitsFromSearchResponse(JsonNode searchResponse) {
            return searchResponse.path("hits").path("total").intValue();
        }
}
