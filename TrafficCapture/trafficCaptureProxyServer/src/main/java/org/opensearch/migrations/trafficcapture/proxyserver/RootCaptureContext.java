package org.opensearch.migrations.trafficcapture.proxyserver;

import io.opentelemetry.api.OpenTelemetry;

import org.opensearch.migrations.tracing.IContextTracker;
import org.opensearch.migrations.trafficcapture.kafkaoffloader.tracing.IRootKafkaOffloaderContext;
import org.opensearch.migrations.trafficcapture.kafkaoffloader.tracing.KafkaRecordContext;
import org.opensearch.migrations.trafficcapture.netty.tracing.RootWireLoggingContext;

import lombok.Getter;

public class RootCaptureContext extends RootWireLoggingContext implements IRootKafkaOffloaderContext {

    public static final String SCOPE_NAME = "captureProxy";
    @Getter
    public final KafkaRecordContext.MetricInstruments kafkaOffloadingInstruments;

    public RootCaptureContext(OpenTelemetry openTelemetry, IContextTracker contextTracker) {
        this(openTelemetry, contextTracker, SCOPE_NAME);
    }

    public RootCaptureContext(OpenTelemetry openTelemetry, IContextTracker contextTracker, String scopeName) {
        super(openTelemetry, contextTracker, scopeName);
        var meter = this.getMeterProvider().get(scopeName);
        kafkaOffloadingInstruments = KafkaRecordContext.makeMetrics(meter);
    }
}
