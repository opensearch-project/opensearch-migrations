package org.opensearch.migrations.trafficcapture.proxyserver;

import io.opentelemetry.api.OpenTelemetry;
import org.opensearch.migrations.trafficcapture.kafkaoffloader.tracing.IRootKafkaOffloaderContext;
import org.opensearch.migrations.trafficcapture.kafkaoffloader.tracing.KafkaRecordContext;
import org.opensearch.migrations.trafficcapture.netty.tracing.RootWireLoggingContext;

public class RootCaptureContext extends RootWireLoggingContext implements IRootKafkaOffloaderContext {
    public RootCaptureContext(OpenTelemetry capture) {
        super(capture);
    }

    @Override
    public KafkaRecordContext.MetricInstruments getKafkaOffloadingInstruments() {
        var meter = getMeterProvider().get("captureProxy");
        return KafkaRecordContext.makeMetrics(meter);
    }
}
