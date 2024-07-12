package org.opensearch.migrations.tracing;

import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;

public class CommonMetricInstruments {
    final LongCounter exceptionCounter;

    public CommonMetricInstruments(Meter meter, String activityName) {
        exceptionCounter = meter.counterBuilder(activityName + "ExceptionCount").build();
    }
}
