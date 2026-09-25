package org.opensearch.migrations.replay.tracing;

import org.opensearch.migrations.replay.identity.ConnectionProcessingId;
import org.opensearch.migrations.replay.identity.KafkaRecordId;
import org.opensearch.migrations.tracing.ActiveContextTracker;
import org.opensearch.migrations.tracing.ActiveContextTrackerByActivityType;
import org.opensearch.migrations.tracing.CompositeContextTracker;
import org.opensearch.migrations.tracing.IContextTracker;
import org.opensearch.migrations.tracing.RootOtelContext;

import io.opentelemetry.api.OpenTelemetry;
import lombok.Getter;
import lombok.NonNull;

@Getter
public class RootReplayerContext extends RootOtelContext implements IRootReplayerContext {
    public static final String SCOPE_NAME = "replayer";

    public final KafkaConsumerContexts.PollScopeContext.MetricInstruments pollInstruments;
    public final KafkaConsumerContexts.CommitScopeContext.MetricInstruments commitInstruments;
    public final KafkaConsumerContexts.KafkaCommitScopeContext.MetricInstruments kafkaCommitInstruments;
    public final KafkaConsumerContexts.RebalanceCallbackScopeContext.MetricInstruments
        rebalanceCallbackInstruments;
    public final ReplayIntakeMetrics replayIntakeMetrics;
    public final TargetAttemptPermitMetrics targetAttemptPermitMetrics;
    public final KafkaCommitStateMetrics kafkaCommitStateMetrics;
    public final ReplayContexts.ConnectionContext.MetricInstruments channelKeyInstruments;
    public final ReplayContexts.KafkaRecordContext.MetricInstruments kafkaRecordInstruments;
    public final ReplayContexts.TrafficStreamLifecycleContext.MetricInstruments trafficStreamLifecycleInstruments;
    public final ReplayContexts.HttpTransactionContext.MetricInstruments httpTransactionInstruments;
    public final ReplayContexts.RequestAccumulationContext.MetricInstruments requestAccumInstruments;
    public final ReplayContexts.ResponseAccumulationContext.MetricInstruments responseAccumInstruments;
    public final ReplayContexts.RequestTransformationContext.MetricInstruments transformationInstruments;
    public final ReplayContexts.ScheduledContext.MetricInstruments scheduledInstruments;
    public final ReplayContexts.TargetRequestContext.MetricInstruments targetRequestInstruments;
    public final ReplayContexts.RequestConnectingContext.MetricInstruments requestConnectingInstruments;
    public final ReplayContexts.RequestSendingContext.MetricInstruments requestSendingInstruments;
    public final ReplayContexts.WaitingForHttpResponseContext.MetricInstruments waitingForHttpResponseInstruments;
    public final ReplayContexts.ReceivingHttpResponseContext.MetricInstruments receivingHttpInstruments;
    public final ReplayContexts.TupleHandlingContext.MetricInstruments tupleHandlingInstruments;
    public final ReplayContexts.SocketContext.MetricInstruments socketInstruments;

    public RootReplayerContext(@NonNull OpenTelemetry sdk) {
        this(
            sdk,
            new CompositeContextTracker(new ActiveContextTracker(), new ActiveContextTrackerByActivityType())
        );
    }

    public RootReplayerContext(@NonNull OpenTelemetry sdk, @NonNull IContextTracker contextTracker) {
        super(SCOPE_NAME, contextTracker, sdk);
        var meter = this.getMeterProvider().get(SCOPE_NAME);

        pollInstruments = KafkaConsumerContexts.PollScopeContext.makeMetrics(meter);
        commitInstruments = KafkaConsumerContexts.CommitScopeContext.makeMetrics(meter);
        kafkaCommitInstruments = KafkaConsumerContexts.KafkaCommitScopeContext.makeMetrics(meter);
        rebalanceCallbackInstruments =
            KafkaConsumerContexts.RebalanceCallbackScopeContext.makeMetrics(meter);
        replayIntakeMetrics = new ReplayIntakeMetrics(meter);
        targetAttemptPermitMetrics = new TargetAttemptPermitMetrics(meter);
        kafkaCommitStateMetrics = new KafkaCommitStateMetrics(meter);
        channelKeyInstruments = ReplayContexts.ConnectionContext.makeMetrics(meter);
        socketInstruments = ReplayContexts.SocketContext.makeMetrics(meter);
        kafkaRecordInstruments = ReplayContexts.KafkaRecordContext.makeMetrics(meter);
        trafficStreamLifecycleInstruments = ReplayContexts.TrafficStreamLifecycleContext.makeMetrics(meter);
        httpTransactionInstruments = ReplayContexts.HttpTransactionContext.makeMetrics(meter);
        requestAccumInstruments = ReplayContexts.RequestAccumulationContext.makeMetrics(meter);
        responseAccumInstruments = ReplayContexts.ResponseAccumulationContext.makeMetrics(meter);
        transformationInstruments = ReplayContexts.RequestTransformationContext.makeMetrics(meter);
        scheduledInstruments = ReplayContexts.ScheduledContext.makeMetrics(meter);
        targetRequestInstruments = ReplayContexts.TargetRequestContext.makeMetrics(meter);
        requestConnectingInstruments = ReplayContexts.RequestConnectingContext.makeMetrics(meter);
        requestSendingInstruments = ReplayContexts.RequestSendingContext.makeMetrics(meter);
        waitingForHttpResponseInstruments = ReplayContexts.WaitingForHttpResponseContext.makeMetrics(meter);
        receivingHttpInstruments = ReplayContexts.ReceivingHttpResponseContext.makeMetrics(meter);
        tupleHandlingInstruments = ReplayContexts.TupleHandlingContext.makeMetrics(meter);
    }

    @Override
    public IReplayContexts.IConnectionContext createConnectionContext(
        @NonNull ConnectionProcessingId connectionProcessingId
    ) {
        return new ReplayContexts.ConnectionContext(this, connectionProcessingId);
    }

    @Override
    public IReplayContexts.IKafkaRecordContext createKafkaRecordContext(
        @NonNull KafkaRecordId recordId,
        int serializedSizeBytes
    ) {
        return new ReplayContexts.KafkaRecordContext(this, recordId, serializedSizeBytes);
    }

    public IKafkaConsumerContexts.IPollScopeContext createPollContext() {
        return new KafkaConsumerContexts.PollScopeContext(this, null);
    }

    public IKafkaConsumerContexts.ICommitScopeContext createCommitContext() {
        return new KafkaConsumerContexts.CommitScopeContext(this, null);
    }

    public IKafkaConsumerContexts.IRebalanceCallbackScopeContext createRebalanceCallbackContext() {
        return new KafkaConsumerContexts.RebalanceCallbackScopeContext(this);
    }
}
