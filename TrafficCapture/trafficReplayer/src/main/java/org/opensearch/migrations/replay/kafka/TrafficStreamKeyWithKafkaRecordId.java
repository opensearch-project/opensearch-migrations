package org.opensearch.migrations.replay.kafka;

// REBUILD-LIMBO(G11) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Carried verbatim. This was the pre-rebuild implementation of a responsibility the design
// reassigns, so it is the input to that refactor rather than something to re-derive. Resolve it to
// dead, keep, or refactor deliberately -- see AGENTS.md section 8a, and read this before writing

// REBUILD-LIMBO-START(G11)
/*

import java.util.StringJoiner;
import java.util.function.Function;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamKeyAndContext;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import lombok.EqualsAndHashCode;
import lombok.Getter;

@EqualsAndHashCode(callSuper = true)
@Getter
class TrafficStreamKeyWithKafkaRecordId extends PojoTrafficStreamKeyAndContext implements KafkaCommitOffsetData {
    private final int generation;
    private final int partition;
    private final long offset;

    TrafficStreamKeyWithKafkaRecordId(
        Function<ITrafficStreamKey, IReplayContexts.IKafkaRecordContext> contextFactory,
        TrafficStream trafficStream,
        KafkaCommitOffsetData ok
    ) {
        this(contextFactory, trafficStream, ok.getGeneration(), ok.getPartition(), ok.getOffset());
    }

    TrafficStreamKeyWithKafkaRecordId(
        Function<ITrafficStreamKey, IReplayContexts.IKafkaRecordContext> contextFactory,
        TrafficStream trafficStream,
        int generation,
        int partition,
        long offset
    ) {
        super(trafficStream);
        this.generation = generation;
        this.partition = partition;
        this.offset = offset;
        var kafkaContext = contextFactory.apply(this);
        this.setTrafficStreamsContext(kafkaContext.createTrafficLifecyleContext(this));
    }

    @Override
    public String toString() {
        return new StringJoiner("|").add(super.toString())
            .add("partition=" + partition)
            .add("offset=" + offset)
            .toString();
    }

    @Override
    public int getSourceGeneration() {
        return generation;
    }
}

*/
// REBUILD-LIMBO-END(G11)