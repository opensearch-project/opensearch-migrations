package com.rfs.http;

import java.util.List;
import java.util.stream.Collectors;

import lombok.Builder;

public class BulkRequestGenerator {

    public static String bulkItemResponse(boolean hasErrors, List<BulkItemResponseEntry> entries) {
        var sb = new StringBuilder();
        sb.append("{\r\n" + //
            "    \"took\": 11,\r\n" + //
            "    \"errors\": " + hasErrors + ",\r\n" + //
            "    \"items\": [\r\n"); //
        sb.append(entries.stream().map(entry -> entry.raw).collect(Collectors.joining(",")));
        sb.append("    ]\r\n" + //
            "}");
        return sb.toString();
    }

    public static BulkItemResponseEntry itemEntry(String itemId) {
        return BulkItemResponseEntry.builder().raw(
            ("        {\r\n" + //
            "            \"index\": {\r\n" + //
            "                \"_index\": \"movies\",\r\n" + //
            "                \"_id\": \"{0}\",\r\n" + //
            "                \"_version\": 1,\r\n" + //
            "                \"result\": \"created\",\r\n" + //
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
            .replaceAll("\\{0\\}", itemId))
            .build();
    }

    public static BulkItemResponseEntry itemEntryFailure(String itemId) {
        return BulkItemResponseEntry.builder().raw(
            ("        {\r\n" + //
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
            "        }\r\n")
            .replaceAll("\\{0\\}", itemId))
            .build();
    }

    @Builder
    public static class BulkItemResponseEntry {
        private String raw;
    }
}
