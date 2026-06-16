package org.opensearch.migrations.tracing;

import lombok.Getter;

public class OtelCollectorEndpoints {
    @Getter
    private final String traceEndpoint;
    @Getter
    private final String metricsEndpoint;

    public OtelCollectorEndpoints(String traceEndpoint, String metricsEndpoint) {
        this.traceEndpoint = traceEndpoint;
        this.metricsEndpoint = metricsEndpoint;
    }

    public static OtelCollectorEndpoints empty() {
        return new OtelCollectorEndpoints(null, null);
    }
}
