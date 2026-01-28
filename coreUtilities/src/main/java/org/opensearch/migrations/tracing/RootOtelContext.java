package org.opensearch.migrations.tracing;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.opensearch.migrations.Utils;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.MeterProvider;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.context.Context;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.semconv.ResourceAttributes;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RootOtelContext implements IRootOtelContext {
    private final OpenTelemetry openTelemetryImpl;
    private final String scopeName;
    @Getter
    private final IContextTracker contextTracker;

    public static OpenTelemetry initializeOpenTelemetryForCollector(
        @NonNull String collectorEndpoint,
        @NonNull String serviceName,
        @NonNull String nodeName
    ) {
        final var spanProcessor = BatchSpanProcessor.builder(
            OtlpGrpcSpanExporter.builder().setEndpoint(collectorEndpoint).setTimeout(2, TimeUnit.SECONDS).build()
        ).build();
        final var metricReader = PeriodicMetricReader.builder(
            OtlpGrpcMetricExporter.builder()
                .setEndpoint(collectorEndpoint)
                // see https://opentelemetry.io/docs/specs/otel/metrics/sdk_exporters/prometheus/
                // "A Prometheus Exporter MUST only support Cumulative Temporality."
                // .setAggregationTemporalitySelector(AggregationTemporalitySelector.deltaPreferred())
                .build()
        ).setInterval(Duration.ofMillis(1000)).build();

        var openTelemetrySdk = OpenTelemetrySdk.builder()
            .setTracerProvider(
                SdkTracerProvider.builder()
                    .setResource(Resource.getDefault()
                        .toBuilder()
                        .put(ResourceAttributes.SERVICE_NAME, serviceName)
                        .put(ResourceAttributes.SERVICE_INSTANCE_ID, nodeName)
                        .build())
                    .addSpanProcessor(spanProcessor)
                    .build()
            )
            .setMeterProvider(
                SdkMeterProvider.builder()
                    .setResource(Resource.getDefault()
                        .toBuilder()
                        .put(ResourceAttributes.SERVICE_NAME, serviceName)
                        .build())
                    .registerMetricReader(metricReader).build()
            )
            .build();

        // Add hook to close SDK, which flushes logs
        Runtime.getRuntime().addShutdownHook(new Thread(openTelemetrySdk::close));
        return openTelemetrySdk;
    }

    public static OpenTelemetry initializeNoopOpenTelemetry() {
        return OpenTelemetrySdk.builder().build();
    }

    /**
     * Initialize the Otel SDK for a collector if collectorEndpoint != null or setup an empty,
     * do-nothing SDK when it is null.
     * @param collectorEndpoint - URL of the otel-collector
     * @param serviceName - name of this service that is sending data to the collector
     * @return a fully initialize OpenTelemetry object capable of producing MeterProviders and TraceProviders
     */
    public static OpenTelemetry initializeOpenTelemetryWithCollectorOrAsNoop(
        String collectorEndpoint,
        @NonNull String serviceName,
        @NonNull String instanceName
    ) {
        return Optional.ofNullable(collectorEndpoint)
            .map(endpoint -> initializeOpenTelemetryForCollector(endpoint, serviceName, instanceName))
            .orElseGet(() -> {
                if (serviceName != null) {
                    log.atWarn()
                        .setMessage("Collector endpoint=null, so serviceName parameter '{}' is being ignored since a no-op OpenTelemetry object is being created")
                        .addArgument(serviceName)
                        .log();
                }
                return initializeNoopOpenTelemetry();
            });
    }

    @Override
    public void onContextCreated(IScopedInstrumentationAttributes newScopedContext) {
        contextTracker.onContextCreated(newScopedContext);
    }

    @Override
    public void onContextClosed(IScopedInstrumentationAttributes newScopedContext) {
        contextTracker.onContextClosed(newScopedContext);
    }

    @Override
    public CommonMetricInstruments getMetrics() {
        return null;
    }

    public RootOtelContext(@NonNull String scopeName,
                           IContextTracker contextTracker,
                           @NonNull String serviceName,
                           @NonNull String instanceName) {
        this(scopeName, contextTracker,
            initializeOpenTelemetryWithCollectorOrAsNoop(null, serviceName, instanceName));
    }

    public RootOtelContext(String scopeName, IContextTracker contextTracker, @NonNull OpenTelemetry sdk) {
        openTelemetryImpl = sdk;
        this.scopeName = scopeName;
        this.contextTracker = contextTracker;
    }

    @Override
    public Exception getObservedExceptionToIncludeInMetrics() {
        return null;
    }

    @Override
    public RootOtelContext getEnclosingScope() {
        return null;
    }

    private OpenTelemetry getOpenTelemetry() {
        return openTelemetryImpl;
    }

    @Override
    public MeterProvider getMeterProvider() {
        return getOpenTelemetry().getMeterProvider();
    }

    private static SpanBuilder addLinkedToBuilder(Stream<Span> linkedSpanContexts, SpanBuilder spanBuilder) {
        return Optional.ofNullable(linkedSpanContexts)
            .map(ss -> ss.collect(Utils.foldLeft(spanBuilder, (b, s) -> b.addLink(s.getSpanContext()))))
            .orElse(spanBuilder);
    }

    private static Span buildSpanWithParent(SpanBuilder builder, Span parentSpan, Stream<Span> linkedSpanContexts) {
        return addLinkedToBuilder(
            linkedSpanContexts,
            Optional.ofNullable(parentSpan)
                .map(p -> builder.setParent(Context.current().with(p)))
                .orElseGet(builder::setNoParent)
        ).startSpan();
    }

    @Override
    public @NonNull Span buildSpan(
        IScopedInstrumentationAttributes forScope,
        String spanName,
        Stream<Span> linkedSpans
    ) {
        var forEnclosingScope = forScope.getEnclosingScope();
        var parentSpan = forEnclosingScope == null ? null : forEnclosingScope.getCurrentSpan();
        var spanBuilder = getOpenTelemetry().getTracer(scopeName).spanBuilder(spanName);
        return buildSpanWithParent(spanBuilder, parentSpan, linkedSpans);
    }
}
