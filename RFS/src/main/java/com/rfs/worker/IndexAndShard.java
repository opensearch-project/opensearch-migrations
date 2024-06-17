package com.rfs.worker;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

@AllArgsConstructor
@Getter
@ToString
public class IndexAndShard {
    String indexName;
    int shard;

    public static String formatAsWorkItemString(String name, int shardId) {
        return name + "__" + shardId;
    }

    public static IndexAndShard valueFromWorkItemString(String input) {
        int lastIndex = input.lastIndexOf("__");
        return new IndexAndShard(input.substring(0, lastIndex),
                Integer.parseInt(input.substring(lastIndex + 2)));
    }
}
