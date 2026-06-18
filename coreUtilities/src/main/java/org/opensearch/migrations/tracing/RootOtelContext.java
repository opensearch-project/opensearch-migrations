package org.opensearch.migrations.tracing;

import java.net.URI;
import java.net.URISyntaxException;
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

    public static OpenTelemetry initializeOpenTelemetryForCollectors(
        @NonNull OtelCollectorEndpoints collectorEndpoints,
        @NonNull String serviceName,
        @NonNull String nodeName
    ) {
        var openTelemetryBuilder = OpenTelemetrySdk.builder();

        var normalizedTraceEndpoint = Optional.ofNullable(collectorEndpoints.getTraceEndpoint())
            .map(endpoint -> normalizeOtlpEndpoint(endpoint, "trace"));
        if (normalizedTraceEndpoint.isPresent()) {
            final var spanProcessor = BatchSpanProcessor.builder(
                OtlpGrpcSpanExporter.builder()
                    .setEndpoint(normalizedTraceEndpoint.get())
                    .setTimeout(2, TimeUnit.SECONDS)
                    .build()
            ).build();

            openTelemetryBuilder = openTelemetryBuilder.setTracerProvider(
                SdkTracerProvider.builder()
                    .setResource(Resource.getDefault()
                        .toBuilder()
                        .put(ResourceAttributes.SERVICE_NAME, serviceName)
                        .put(ResourceAttributes.SERVICE_INSTANCE_ID, nodeName)
                        .build())
                    .addSpanProcessor(spanProcessor)
                    .build()
            );
        }

        var normalizedMetricsEndpoint = Optional.ofNullable(collectorEndpoints.getMetricsEndpoint())
            .map(endpoint -> normalizeOtlpEndpoint(endpoint, "metrics"));
        if (normalizedMetricsEndpoint.isPresent()) {
            final var metricReader = PeriodicMetricReader.builder(
                OtlpGrpcMetricExporter.builder()
                    .setEndpoint(normalizedMetricsEndpoint.get())
                    // see https://opentelemetry.io/docs/specs/otel/metrics/sdk_exporters/prometheus/
                    // "A Prometheus Exporter MUST only support Cumulative Temporality."
                    // .setAggregationTemporalitySelector(AggregationTemporalitySelector.deltaPreferred())
                    .build()
            ).setInterval(Duration.ofMillis(1000)).build();

            openTelemetryBuilder = openTelemetryBuilder.setMeterProvider(
                SdkMeterProvider.builder()
                    .setResource(Resource.getDefault()
                        .toBuilder()
                        .put(ResourceAttributes.SERVICE_NAME, serviceName)
                        .build())
                    .registerMetricReader(metricReader).build()
            );
        }

        var openTelemetrySdk = openTelemetryBuilder.build();

        // Add hook to close SDK, which flushes logs
        Runtime.getRuntime().addShutdownHook(new Thread(openTelemetrySdk::close));
        return openTelemetrySdk;
    }

    public static OpenTelemetry initializeNoopOpenTelemetry() {
        return OpenTelemetrySdk.builder().build();
    }

    static String normalizeOtlpEndpoint(@NonNull String endpoint, @NonNull String signalName) {
        var trimmedEndpoint = endpoint.trim();
        if (trimmedEndpoint.isEmpty()) {
            throw new IllegalArgumentException("OpenTelemetry " + signalName +
                " endpoint cannot be blank; omit the endpoint option to disable " + signalName + " export");
        }
        var normalizedEndpoint = trimmedEndpoint.startsWith("http://") || trimmedEndpoint.startsWith("https://")
            ? trimmedEndpoint
            : "http://" + trimmedEndpoint;
        try {
            var uri = new URI(normalizedEndpoint);
            if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) {
                throw new IllegalArgumentException("OpenTelemetry " + signalName +
                    " endpoint must use http or https: " + endpoint);
            }
            if (uri.getHost() == null || uri.getHost().isEmpty()) {
                throw new IllegalArgumentException("OpenTelemetry " + signalName +
                    " endpoint must include a host: " + endpoint);
            }
            return normalizedEndpoint;
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("OpenTelemetry " + signalName +
                " endpoint is not a valid URI: " + endpoint, e);
        }
    }

    /**
     * Initialize the Otel SDK for collectors when at least one signal endpoint is configured, or setup an empty,
     * do-nothing SDK when all endpoints are null.
     * @param collectorEndpoints - URLs of the otel-collectors by signal
     * @param serviceName - name of this service that is sending data to the collector
     * @return a fully initialize OpenTelemetry object capable of producing MeterProviders and TraceProviders
     */
    public static OpenTelemetry initializeOpenTelemetryWithCollectorsOrAsNoop(
        OtelCollectorEndpoints collectorEndpoints,
        @NonNull String serviceName,
        @NonNull String instanceName
    ) {
        return Optional.ofNullable(collectorEndpoints)
            .filter(endpoints -> endpoints.getTraceEndpoint() != null || endpoints.getMetricsEndpoint() != null)
            .map(endpoints -> initializeOpenTelemetryForCollectors(endpoints, serviceName, instanceName))
            .orElseGet(() -> {
                if (serviceName != null) {
                    log.atWarn().setMessage("Collector endpoints are not configured, so serviceName parameter '{}'" +
                            " is being ignored since a no-op OpenTelemetry object is being created")
                        .addArgument(serviceName).log();
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
            initializeOpenTelemetryWithCollectorsOrAsNoop(OtelCollectorEndpoints.empty(), serviceName, instanceName));
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
