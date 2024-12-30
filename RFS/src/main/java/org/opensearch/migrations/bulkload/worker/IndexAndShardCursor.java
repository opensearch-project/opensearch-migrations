package org.opensearch.migrations.bulkload.worker;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

@AllArgsConstructor
@Getter
@ToString
public class IndexAndShardCursor {
    public static final String SEPARATOR = "__";
    String indexName;
    int shard;
    int startingSegmentIndex;
    int startingDocId;

    public static String formatAsWorkItemString(String name, int shardId) {
        if (name.contains(SEPARATOR)) {
            throw new IllegalArgumentException(
                "Illegal work item name: '" + name + "'.  " + "Work item names cannot contain '" + SEPARATOR + "'"
            );
        }
        return name + SEPARATOR + shardId;
    }

    public static String formatAsWorkItemString(String name, int shardId, int startingSegmentIndex, int startingDocId) {
        if (name.contains(SEPARATOR)) {
            throw new IllegalArgumentException(
                    "Illegal work item name: '" + name + "'.  " + "Work item names cannot contain '" + SEPARATOR + "'"
            );
        }
        return name + SEPARATOR + shardId + SEPARATOR + startingSegmentIndex + SEPARATOR + startingDocId;
    }

    public static IndexAndShardCursor valueFromWorkItemString(String input) {
        var components = input.split(SEPARATOR + "+");
        if (components.length < 2) {
            throw new IllegalArgumentException("Illegal work item name: '" + input + "'");
        }

        return new IndexAndShardCursor(components[0], Integer.parseInt(components[1]),
                components.length >= 3 ? Integer.parseInt(components[2]) : 0,
                components.length >= 4 ? Integer.parseInt(components[3]) : 0);
    }
}
