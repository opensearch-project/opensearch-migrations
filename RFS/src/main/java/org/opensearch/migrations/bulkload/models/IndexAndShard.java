package org.opensearch.migrations.bulkload.models;

import lombok.AllArgsConstructor;
import lombok.Getter;

public interface IndexAndShard {
    String getIndexName();
    int getShardId();

    @Getter
    @AllArgsConstructor
    class SimpleIndexAndShard implements IndexAndShard {
        String indexName;
        int shardId;
    }
}
