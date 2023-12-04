package org.opensearch.migrations.replay.kafka;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class PojoKafkaCommitOffsetData implements KafkaCommitOffsetData {
    final int generation;
    final int partition;
    final long offset;
}
