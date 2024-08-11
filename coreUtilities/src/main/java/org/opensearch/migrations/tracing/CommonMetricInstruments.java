package org.opensearch.migrations.tracing;

import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;

import lombok.AllArgsConstructor;

public class CommonMetricInstruments {
    @AllArgsConstructor
    public static class Labels {
        public final String exception;

        public static Labels fromActivityName(String activityName) {
            return new Labels(activityName + "ExceptionCount");
        }
    }

    public final LongCounter exceptionCounter;

    public CommonMetricInstruments(Meter meter, String activityName) {
        this(meter, Labels.fromActivityName(activityName));
    }

    public CommonMetricInstruments(Meter meter, Labels labels) {
        exceptionCounter = meter.counterBuilder(labels.exception).build();
    }
}
