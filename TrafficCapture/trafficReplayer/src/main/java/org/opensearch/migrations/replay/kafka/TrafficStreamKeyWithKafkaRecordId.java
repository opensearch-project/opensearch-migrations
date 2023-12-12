package org.opensearch.migrations.replay.kafka;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamKey;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import java.util.StringJoiner;

@EqualsAndHashCode(callSuper = true)
@Getter
class TrafficStreamKeyWithKafkaRecordId extends PojoTrafficStreamKey implements KafkaCommitOffsetData {
    private final int generation;
    private final int partition;
    private final long offset;

    TrafficStreamKeyWithKafkaRecordId(TrafficStream trafficStream, KafkaCommitOffsetData ok) {
        this(trafficStream, ok.getGeneration(), ok.getPartition(), ok.getOffset());
    }

    TrafficStreamKeyWithKafkaRecordId(TrafficStream trafficStream, int generation, int partition, long offset) {
        super(trafficStream);
        this.generation = generation;
        this.partition = partition;
        this.offset = offset;
    }

    @Override
    public String toString() {
        return new StringJoiner("|")
                .add(super.toString())
                .add("partition=" + partition)
                .add("offset=" + offset)
                .toString();
    }
}
