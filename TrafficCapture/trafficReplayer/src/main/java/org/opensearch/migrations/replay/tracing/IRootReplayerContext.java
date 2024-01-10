package org.opensearch.migrations.replay.tracing;

import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import org.opensearch.migrations.tracing.IRootOtelContext;

public interface IRootReplayerContext extends IRootOtelContext {
    LongHistogram getChannelDuration();
    LongHistogram getKafkaRecordDuration();
    LongHistogram getTrafficStreamLifecycleDuration();
    LongHistogram getHttpTransactionDuration();
    LongHistogram getRequestAccumulationDuration();
    LongHistogram getResponseAccumulationDuration();
    LongHistogram getRequestTransformationDuration();
    LongHistogram getScheduledDuration();
    LongHistogram getTargetRequestDuration();
    LongHistogram getRequestSendingDuration();
    LongHistogram getWaitingForResponseDuration();
    LongHistogram getReceivingResponseDuration();
    LongHistogram getTupleHandlingDuration();

    LongHistogram getKafkaTouchDuration();
    LongHistogram getKafkaPollDuration();
    LongHistogram getCommitDuration();
    LongHistogram getKafkaCommitDuration();

    LongHistogram getReadChunkDuration();
    LongHistogram getBackPressureDuration();
    LongHistogram getWaitForNextSignalDuration();


    LongCounter getChannelCounter();
    LongCounter getKafkaRecordCounter();
    LongCounter getTrafficStreamLifecycleCounter();
    LongCounter getHttpTransactionCounter();
    LongCounter getRequestAccumulationCounter();
    LongCounter getResponseAccumulationCounter();
    LongCounter getRequestTransformationCounter();
    LongCounter getScheduledCounter();
    LongCounter getTargetRequestCounter();
    LongCounter getRequestSendingCounter();
    LongCounter getWaitingForResponseCounter();
    LongCounter getReceivingResponseCounter();
    LongCounter getTupleHandlingCounter();


    LongCounter getKafkaTouchCounter();
    LongCounter getKafkaPollCounter();
    LongCounter getCommitCounter();
    LongCounter getKafkaCommitCounter();

    LongCounter getReadChunkCounter();
    LongCounter getBackPressureCounter();
    LongCounter getWaitForNextSignalCounter();

    LongUpDownCounter getActiveChannelsCounter();
    LongCounter getKafkaRecordBytesCounter();
    LongCounter getKafkaPartitionsRevokedCounter();
    LongCounter getKafkaPartitionsAssignedCounter();
    LongUpDownCounter getKafkaActivePartitionsCounter();
}
