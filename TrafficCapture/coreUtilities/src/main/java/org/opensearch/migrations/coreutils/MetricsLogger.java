package org.opensearch.migrations.coreutils;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter;
import io.opentelemetry.instrumentation.log4j.appender.v2_17.OpenTelemetryAppender;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.semconv.resource.attributes.ResourceAttributes;
import org.slf4j.Logger;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;

@Slf4j
public
class MetricsLogger {
    private Logger logger;

    /**
     * Creates a MetricLogger instance.
     *
     * @param source Generally the name of the class where the logger is being instantiated, this
     *               will be combined with `MetricsLogger.` to form the logger name.
     * @return A MetricsLogger instance.
     */
    public MetricsLogger(String source) {
        logger = LoggerFactory.getLogger(String.format("MetricsLogger.%s", source));
    }

    public static void initializeOpenTelemetry(String serviceName, String collectorEndpoint) {
        OpenTelemetrySdk sdk =
                OpenTelemetrySdk.builder()
                        .setLoggerProvider(
                                SdkLoggerProvider.builder()
                                        .setResource(
                                                Resource.getDefault().toBuilder()
                                                        .put(ResourceAttributes.SERVICE_NAME, serviceName)
                                                        .build())
                                        .addLogRecordProcessor(
                                                BatchLogRecordProcessor.builder(
                                                                OtlpGrpcLogRecordExporter.builder()
                                                                        .setEndpoint(collectorEndpoint)
                                                                        .build())
                                                        .build())
                                        .build())
                        .build();
        GlobalOpenTelemetry.set(sdk);

        // Add hook to close SDK, which flushes logs
        Runtime.getRuntime().addShutdownHook(new Thread(sdk::close));
        OpenTelemetryAppender.install(GlobalOpenTelemetry.get());
    }


    /**
     * To indicate a successful event (e.g. data received or data sent) that may be a helpful
     * metric, this method can be used to return a LoggingEventBuilder. The LoggingEventBuilder
     * object can be supplemented with key-value pairs (used for diagnostic information and
     * dimensions for the metric) and a log message, and then logged. As an example,
     *  metricsLogger.atSuccess().addKeyValue("key", "value").setMessage("Task succeeded").log();
     */
    public MetricsLogBuilder atSuccess(MetricsEvent event) {
        return new MetricsLogBuilder(logger).atSuccess(event);
    }

    /**
     * Indicates a failed event that may be a helpful metric. This method returns a LoggingEventBuilder
     * which should be supplemented with diagnostic information or dimensions for the metric (as
     * key-value pairs) and a log message.
     * @param cause The exception thrown in the failure, this will be set as the cause for the log message.
     */
    public MetricsLogBuilder atError(MetricsEvent event, Throwable cause) {
        if (cause == null) {
            return atError(event);
        }
        return new MetricsLogBuilder(logger).atError(event)
                .setAttribute(MetricsAttributeKey.EXCEPTION_MESSAGE, cause.getMessage())
                .setAttribute(MetricsAttributeKey.EXCEPTION_STACKTRACE, cause.getStackTrace().toString())
                .setAttribute(MetricsAttributeKey.EXCEPTION_TYPE, cause.getClass().getName());
    }

    /**
     * This also indicates a failed event that may be a helpful metric. It can be used in cases where
     * there is a failure that isn't indicated by an Exception being thrown.
     */
    public MetricsLogBuilder atError(MetricsEvent event) {
        return new MetricsLogBuilder(logger).atError(event);
    }

    public MetricsLogBuilder atTrace(MetricsEvent event) {
        return new MetricsLogBuilder(logger).atTrace(event);
    }
}
