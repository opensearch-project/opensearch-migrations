package org.opensearch.migrations.replay.kafka;

import lombok.AllArgsConstructor;
import lombok.Getter;

public interface KafkaCommitOffsetData {
    int getPartition();
    long getOffset();
    int getGeneration();

    @Getter
    @AllArgsConstructor
    public static class PojoKafkaCommitOffsetData implements KafkaCommitOffsetData {
        final int generation;
        final int partition;
        final long offset;
    }
}
