package org.opensearch.migrations.replay.kafka;

import lombok.AllArgsConstructor;
import lombok.Getter;

public interface KafkaCommitOffsetData {
    int getPartition();
    long getOffset();
    int getGeneration();

}
