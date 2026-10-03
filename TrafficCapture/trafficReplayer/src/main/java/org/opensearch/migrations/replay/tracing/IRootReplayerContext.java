package org.opensearch.migrations.replay.tracing;

import org.opensearch.migrations.replay.identity.ConnectionProcessingId;
import org.opensearch.migrations.replay.identity.KafkaRecordId;
import org.opensearch.migrations.replay.intake.ReplayIntakeOwner;
import org.opensearch.migrations.replay.kafkasource.KafkaSourceOwner;
import org.opensearch.migrations.replay.lifecycle.TargetAttemptPermitProvider;
import org.opensearch.migrations.tracing.IInstrumentConstructor;
import org.opensearch.migrations.tracing.IRootOtelContext;

public interface IRootReplayerContext extends IRootOtelContext, IInstrumentConstructor {

    IReplayContexts.IConnectionContext createConnectionContext(
        ConnectionProcessingId connectionProcessingId
    );

    IReplayContexts.IKafkaRecordContext createKafkaRecordContext(
        KafkaRecordId recordId,
        int serializedSizeBytes
    );

    ReplayIntakeOwner.Metrics getReplayIntakeMetrics();

    KafkaSourceOwner.Metrics getKafkaCommitStateMetrics();

    TargetAttemptPermitProvider.Metrics getTargetAttemptPermitMetrics();
}
