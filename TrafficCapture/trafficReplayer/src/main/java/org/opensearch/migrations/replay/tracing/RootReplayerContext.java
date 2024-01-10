package org.opensearch.migrations.replay.tracing;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.MeterProvider;
import org.opensearch.migrations.tracing.RootOtelContext;

import lombok.Getter;

@Getter
public class RootReplayerContext extends RootOtelContext<IRootReplayerContext> implements IRootReplayerContext {
    public final KafkaConsumerContexts.AsyncListeningContext.MetricInstruments asyncListeningInstruments;
    public final KafkaConsumerContexts.TouchScopeContext.MetricInstruments touchInstruments;
    public final KafkaConsumerContexts.PollScopeContext.MetricInstruments pollInstruments;

    public RootReplayerContext(OpenTelemetry sdk) {
        super(sdk);
        var meterProvider = this.getMeterProvider();
        asyncListeningInstruments = new KafkaConsumerContexts.AsyncListeningContext.MetricInstruments(meterProvider);
        touchInstruments = new KafkaConsumerContexts.TouchScopeContext.MetricInstruments(meterProvider);

    }
}
