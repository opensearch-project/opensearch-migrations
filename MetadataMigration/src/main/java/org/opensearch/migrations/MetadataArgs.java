package org.opensearch.migrations;

import com.beust.jcommander.Parameter;

public class MetadataArgs {
    @Parameter(names = {"--help", "-h"}, help = true, description = "Displays information about how to use this tool")
    public boolean help;

    @Parameter(
        names = { "--otel-trace-collector-endpoint", "--otelTraceCollectorEndpoint" },
        description = "Endpoint for the OpenTelemetry Collector to which traces should be forwarded. " +
            "Omit this option to disable trace export.")
    public String otelTraceCollectorEndpoint;

    @Parameter(
        names = { "--otel-metrics-collector-endpoint", "--otelMetricsCollectorEndpoint" },
        description = "Endpoint for the OpenTelemetry Collector to which metrics should be forwarded. " +
            "Omit this option to disable metric export.")
    public String otelMetricsCollectorEndpoint;
}
