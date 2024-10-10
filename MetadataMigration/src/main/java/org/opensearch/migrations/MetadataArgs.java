package org.opensearch.migrations;

import com.beust.jcommander.Parameter;

public class MetadataArgs {
    @Parameter(names = {"--help", "-h"}, help = true, description = "Displays information about how to use this tool")
    public boolean help;

    @Parameter(names = { "--otel-collector-endpoint" }, description = "Endpoint (host:port) for the OpenTelemetry Collector to which metrics logs should be"
            + "forwarded. If no value is provided, metrics will not be forwarded.")
    public String otelCollectorEndpoint;

    @Parameter(names = { "--config-file", "-c" }, description = "The path to a config file")
    public String configFile;
}
