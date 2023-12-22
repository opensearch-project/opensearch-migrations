package org.opensearch.migrations.replay.kafka;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamKeyAndContext;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.tracing.ReplayContexts;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import java.util.StringJoiner;
import java.util.function.Function;

@EqualsAndHashCode(callSuper = true)
@Getter
class TrafficStreamKeyWithKafkaRecordId extends PojoTrafficStreamKeyAndContext implements KafkaCommitOffsetData {
    private final int generation;
    private final int partition;
    private final long offset;

    TrafficStreamKeyWithKafkaRecordId(Function<ITrafficStreamKey, IReplayContexts.IChannelKeyContext> contextFactory,
                                      TrafficStream trafficStream, String recordId, KafkaCommitOffsetData ok) {
        this(contextFactory, trafficStream, recordId, ok.getGeneration(), ok.getPartition(), ok.getOffset());
    }

    TrafficStreamKeyWithKafkaRecordId(Function<ITrafficStreamKey, IReplayContexts.IChannelKeyContext> contextFactory,
                                      TrafficStream trafficStream, String recordId,
                                      int generation, int partition, long offset) {
        super(trafficStream);
        this.generation = generation;
        this.partition = partition;
        this.offset = offset;
        var channelKeyContext = contextFactory.apply(this);
        var kafkaContext = new ReplayContexts.KafkaRecordContext(channelKeyContext, recordId);
        this.setTrafficStreamsContext(new ReplayContexts.TrafficStreamsLifecycleContext(kafkaContext, this));
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
