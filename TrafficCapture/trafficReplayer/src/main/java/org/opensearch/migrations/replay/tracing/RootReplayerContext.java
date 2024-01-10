package org.opensearch.migrations.replay.tracing;

import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.MeterProvider;
import org.opensearch.migrations.tracing.RootOtelContext;

import lombok.Getter;

@Getter
public class RootReplayerContext extends RootOtelContext implements IRootReplayerContext {
    public final LongHistogram channelDuration;
    public final LongHistogram kafkaRecordDuration;
    public final LongHistogram trafficStreamLifecycleDuration;
    public final LongHistogram httpTransactionDuration;
    public final LongHistogram requestAccumulationDuration;
    public final LongHistogram responseAccumulationDuration;
    public final LongHistogram requestTransformationDuration;
    public final LongHistogram scheduledDuration;
    public final LongHistogram targetRequestDuration;
    public final LongHistogram requestSendingDuration;
    public final LongHistogram waitingForResponseDuration;
    public final LongHistogram receivingResponseDuration;
    public final LongHistogram tupleHandlingDuration;
    
    public final LongHistogram kafkaTouchDuration;
    public final LongHistogram kafkaPollDuration;
    public final LongHistogram commitDuration;
    public final LongHistogram kafkaCommitDuration;

    public final LongHistogram readChunkDuration;
    public final LongHistogram backPressureDuration;
    public final LongHistogram waitForNextSignalDuration;


    public final LongCounter channelCounter;
    public final LongCounter kafkaRecordCounter;
    public final LongCounter trafficStreamLifecycleCounter;
    public final LongCounter httpTransactionCounter;
    public final LongCounter requestAccumulationCounter;
    public final LongCounter responseAccumulationCounter;
    public final LongCounter requestTransformationCounter;
    public final LongCounter scheduledCounter;
    public final LongCounter targetRequestCounter;
    public final LongCounter requestSendingCounter;
    public final LongCounter waitingForResponseCounter;
    public final LongCounter receivingResponseCounter;
    public final LongCounter tupleHandlingCounter;
    
    public final LongCounter kafkaTouchCounter;
    public final LongCounter kafkaPollCounter;
    public final LongCounter commitCounter;
    public final LongCounter kafkaCommitCounter;

    public final LongCounter readChunkCounter;
    public final LongCounter backPressureCounter;
    public final LongCounter waitForNextSignalCounter;

    public final LongUpDownCounter activeChannelsCounter;
    public final LongCounter kafkaRecordBytesCounter;
    public final LongCounter kafkaPartitionsRevokedCounter;
    public final LongCounter kafkaPartitionsAssignedCounter;
    public final LongUpDownCounter kafkaActivePartitionsCounter;

    public RootReplayerContext(MeterProvider meterProvider) {
        channelDuration = buildHistogram(meterProvider, );
        kafkaRecordDuration = buildHistogram(meterProvider, );;
        trafficStreamLifecycleDuration = buildHistogram(meterProvider, );
        httpTransactionDuration = buildHistogram(meterProvider, );
        requestAccumulationDuration = buildHistogram(meterProvider, );
        responseAccumulationDuration = buildHistogram(meterProvider, );
        requestTransformationDuration = buildHistogram(meterProvider, );
        scheduledDuration = buildHistogram(meterProvider, );
        targetRequestDuration = buildHistogram(meterProvider, );
        requestSendingDuration = buildHistogram(meterProvider, );
        waitingForResponseDuration = buildHistogram(meterProvider, );
        receivingResponseDuration = buildHistogram(meterProvider, );
        tupleHandlingDuration = buildHistogram(meterProvider, );

        kafkaTouchDuration = buildHistogram(meterProvider, );
        kafkaPollDuration = buildHistogram(meterProvider, );
        commitDuration = buildHistogram(meterProvider, );
        kafkaCommitDuration = buildHistogram(meterProvider, );

        readChunkDuration = buildHistogram(meterProvider, );
        backPressureDuration = buildHistogram(meterProvider, );
        waitForNextSignalDuration = buildHistogram(meterProvider, );


        channelCounter = buildCounter(meterProvider, );
        kafkaRecordCounter = buildCounter(meterProvider, );
        trafficStreamLifecycleCounter = buildCounter(meterProvider, );
        httpTransactionCounter = buildCounter(meterProvider, );
        requestAccumulationCounter = buildCounter(meterProvider, );
        responseAccumulationCounter = buildCounter(meterProvider, );
        requestTransformationCounter = buildCounter(meterProvider, );
        scheduledCounter = buildCounter(meterProvider, );
        targetRequestCounter = buildCounter(meterProvider, );
        requestSendingCounter = buildCounter(meterProvider, );
        waitingForResponseCounter = buildCounter(meterProvider, );
        receivingResponseCounter = buildCounter(meterProvider, );
        tupleHandlingCounter = buildCounter(meterProvider, );

        kafkaTouchCounter = buildCounter(meterProvider, );
        kafkaPollCounter = buildCounter(meterProvider, );
        commitCounter = buildCounter(meterProvider, );
        kafkaCommitCounter = buildCounter(meterProvider, );

        readChunkCounter = buildCounter(meterProvider, );
        backPressureCounter = buildCounter(meterProvider, );
        waitForNextSignalCounter = buildCounter(meterProvider, );

        activeChannelsCounter = buildUpDownCounter(meterProvider, IReplayContexts.MetricNames.KAFKA_RECORD_READ);
        kafkaRecordBytesCounter = buildCounter(meterProvider, IReplayContexts.MetricNames.KAFKA_BYTES_READ);
        kafkaPartitionsRevokedCounter =
                buildCounter(meterProvider, IKafkaConsumerContexts.MetricNames.PARTITIONS_REVOKED_EVENT_COUNT);
        kafkaPartitionsAssignedCounter =
                buildCounter(meterProvider, IKafkaConsumerContexts.MetricNames.PARTITIONS_ASSIGNED_EVENT_COUNT);
        kafkaActivePartitionsCounter =
                buildUpDownCounter(meterProvider, IKafkaConsumerContexts.MetricNames.ACTIVE_PARTITIONS_ASSIGNED_COUNT);
    }

    private static LongCounter buildCounter(MeterProvider meterProvider, String eventName) {
        meterProvider.get();
    }

    private static LongCounter buildHistogram(MeterProvider meterProvider, String eventName) {

    }

    private static LongUpDownCounter buildUpDownCounter(MeterProvider meterProvider, String eventName) {

    }
}
