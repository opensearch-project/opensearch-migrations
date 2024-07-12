package org.opensearch.migrations.replay.kafka;

public interface KafkaCommitOffsetData {
    int getPartition();

    long getOffset();

    int getGeneration();

}
