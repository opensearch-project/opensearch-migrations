package com.rfs.worker;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

@AllArgsConstructor
@Getter
@ToString
public class IndexAndShard {
    public static final String SEPARATOR = "__";
    String indexName;
    int shard;

    public static String formatAsWorkItemString(String name, int shardId) {
        if (name.contains(SEPARATOR)) {
            throw new IllegalArgumentException(
                "Illegal work item name: '" + name + "'.  " + "Work item names cannot contain '" + SEPARATOR + "'"
            );
        }
        return name + SEPARATOR + shardId;
    }

    public static IndexAndShard valueFromWorkItemString(String input) {
        int lastIndex = input.lastIndexOf(SEPARATOR);
        return new IndexAndShard(input.substring(0, lastIndex), Integer.parseInt(input.substring(lastIndex + 2)));
    }
}
