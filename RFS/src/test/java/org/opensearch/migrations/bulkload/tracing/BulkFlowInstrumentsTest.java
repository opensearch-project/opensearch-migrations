package org.opensearch.migrations.bulkload.tracing;

import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.DocumentExceptionAllowlist;
import org.opensearch.migrations.bulkload.common.RestClient;
import org.opensearch.migrations.bulkload.common.http.CompressionMode;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.common.http.HttpResponse;
import org.opensearch.migrations.bulkload.common.retry.AdaptiveLimit;
import org.opensearch.migrations.bulkload.common.retry.BulkErrorClass;
import org.opensearch.migrations.bulkload.common.retry.RetryBudget;
import org.opensearch.migrations.bulkload.http.BulkRequestGenerator;
import org.opensearch.migrations.bulkload.pipeline.model.Document;
import org.opensearch.migrations.bulkload.version_os_2_11.OpenSearchClient_OS_2_11;
import org.opensearch.migrations.reindexer.FailedRequestsLogger;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.metrics.data.MetricData;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Drives a client through throttles, rejections, a permanent failure and slot contention; every instrument reports. */
class BulkFlowInstrumentsTest {
    private final List<String> requestIds = new CopyOnWriteArrayList<>();

    private static List<String> idsIn(byte[] body) {
        var ids = new ArrayList<String>();
        var lines = new String(body, StandardCharsets.UTF_8).split("\n");
        for (int i = 0; i < lines.length; i += 2) {
            var start = lines[i].indexOf("\"_id\":\"") + 7;
            ids.add(lines[i].substring(start, lines[i].indexOf('"', start)));
        }
        return ids;
    }

    private static HttpResponse created(String id) {
        return new HttpResponse(200, "OK", Map.of(),
            BulkRequestGenerator.bulkItemResponse(false, List.of(BulkRequestGenerator.itemEntry(id))));
    }

    private HttpResponse answer(String id) {
        long seen = requestIds.stream().filter(id::equals).count();
        requestIds.add(id);
        return switch (id) {
            case "throttled" -> seen == 0 ? new HttpResponse(429, "", Map.of("Retry-After", "0"), "{}") : created(id);
            case "rejected" -> seen == 0 ? new HttpResponse(200, "OK", Map.of(), BulkRequestGenerator.bulkItemResponse(
                true, List.of(BulkRequestGenerator.itemEntryRetryableFailure(id)))) : created(id);
            case "forbidden" -> new HttpResponse(403, "", Map.of(), "{}");
            case "unavailable" -> seen == 0 ? new HttpResponse(503, "", Map.of(), "{}") : created(id);
            default -> created(id);
        };
    }

    private static List<String> instrumentNames() {
        var names = new ArrayList<String>();
        for (var field : BulkFlowInstruments.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                try {
                    names.add((String) field.get(null));
                } catch (IllegalAccessException e) {
                    throw new AssertionError(e);
                }
            }
        }
        return names;
    }

    @Test
    void everyInstrumentReports() {
        var context = DocumentMigrationTestContext.factory().withAllTracking();
        var restClient = mock(RestClient.class);
        var connection = mock(ConnectionContext.class);
        when(connection.getUri()).thenReturn(URI.create("http://localhost/"));
        when(restClient.getConnectionContext()).thenReturn(connection);
        when(restClient.postAsyncBytes(any(), any(), any(), any())).thenAnswer(invocation -> {
            byte[] body = invocation.getArgument(1);
            return Mono.just(answer(idsIn(body).get(0))).delayElement(Duration.ofMillis(20));
        });
        var client = new OpenSearchClient_OS_2_11(restClient, new FailedRequestsLogger(), Version.fromString("OS 2.11"),
            CompressionMode.UNCOMPRESSED, 1, 10_000_000);
        // One throttle retry spends the whole budget, so the second has to wait for it.
        var budget = new RetryBudget(5);
        var limit = new AdaptiveLimit(2);
        client.setBulkRetryBudget(budget);
        client.setBulkInFlightLimit(limit);
        client.setBulkFlowMetrics(context.makeBulkFlowInstruments(budget, limit));

        // Two at a time against a limit that starts at 1: slot waits, then growth to the max.
        Flux.just("a", "b", "c", "d", "throttled", "rejected", "unavailable", "forbidden")
            .flatMap(id -> client.sendBulkRequestRaw("idx", List.of(new Document(id, "{}".getBytes(StandardCharsets.UTF_8),
                    Document.Operation.UPSERT, Map.of(), Map.of())), null, false, DocumentExceptionAllowlist.empty())
                .onErrorResume(e -> Mono.empty()), 2)
            .blockLast(Duration.ofSeconds(60));
        // One at a time: while a cut limit is below 2 each lone request takes the last slot and grows it; once it is
        // back at 2 a lone request leaves a slot unused, so its success isn't the bottleneck. The throttle and the
        // rejection may each cut, and below 1 the limit grows by 1/32 per success.
        for (int i = 0; i < 64 && limit.limit() < 2; i++) {
            client.sendBulkRequestRaw("idx", List.of(new Document("regrow" + i, "{}".getBytes(StandardCharsets.UTF_8),
                Document.Operation.UPSERT, Map.of(), Map.of())), null, false, DocumentExceptionAllowlist.empty()).block();
        }
        for (int i = 0; i < 2; i++) {
            client.sendBulkRequestRaw("idx", List.of(new Document("alone" + i, "{}".getBytes(StandardCharsets.UTF_8),
                Document.Operation.UPSERT, Map.of(), Map.of())), null, false, DocumentExceptionAllowlist.empty()).block();
        }

        Set<String> reported = context.inMemoryInstrumentationBundle.getFinishedMetrics().stream()
            .map(MetricData::getName).collect(Collectors.toCollection(TreeSet::new));
        var missing = new TreeSet<>(instrumentNames());
        missing.removeAll(reported);
        missing.remove(BulkFlowInstruments.BULK_BATCH_SPLITS);         // aSplitAndAGiveUpAreCounted
        missing.remove(BulkFlowInstruments.BULK_DOCS_GAVE_UP);         // aSplitAndAGiveUpAreCounted
        Assertions.assertEquals(Set.of(), missing, "reported: " + reported);

        var summary = client.getBulkFlowSummary();
        Assertions.assertEquals(2, summary.throttleRetries());
        Assertions.assertEquals(2.0, summary.highestLimit(), 1e-9);
        // A cut multiplies the limit by a random 0.5-0.7. The first is from 2; the rejection cuts again if it took its
        // slot after the throttle's cut, from wherever the limit had regrown to, at least 1.
        Assertions.assertTrue(summary.limitCuts() == 1 || summary.limitCuts() == 2, "cuts " + summary.limitCuts());
        Assertions.assertTrue(summary.lowestLimit() >= 0.5 && summary.lowestLimit() <= 1.4,
            "lowest limit " + summary.lowestLimit());
        Assertions.assertTrue(summary.budgetWaitMillis() > 0);
    }

    @Test
    void retryDelayKeepsItsErrorClass() {
        var context = DocumentMigrationTestContext.factory().withAllTracking();
        var instruments = context.makeBulkFlowInstruments(null, null);

        instruments.onRetry(BulkErrorClass.THROTTLE, 123, false);

        var errorClasses = context.inMemoryInstrumentationBundle.getFinishedMetrics().stream()
            .filter(m -> m.getName().equals(BulkFlowInstruments.BULK_RETRY_DELAY))
            .flatMap(m -> m.getHistogramData().getPoints().stream())
            .map(p -> p.getAttributes().get(BulkFlowInstruments.ERROR_CLASS))
            .collect(Collectors.toSet());
        Assertions.assertEquals(Set.of("throttle"), errorClasses);
    }

    @Test
    void requestPreparationStagesAreReported() {
        var context = DocumentMigrationTestContext.factory().withAllTracking();
        var restClient = mock(RestClient.class);
        var connection = mock(ConnectionContext.class);
        when(connection.getUri()).thenReturn(URI.create("http://localhost/"));
        when(restClient.getConnectionContext()).thenReturn(connection);
        when(restClient.postAsyncBytes(any(), any(), any(), any())).thenReturn(Mono.just(created("a")));
        var client = new OpenSearchClient_OS_2_11(restClient, new FailedRequestsLogger(), Version.fromString("OS 2.11"),
            CompressionMode.GZIP_BODY_COMPRESSION, 100, 10_000_000);
        client.setBulkFlowMetrics(context.makeBulkFlowInstruments(null, null));

        client.sendBulkRequestRaw("idx", List.of(new Document("a", "{}".getBytes(StandardCharsets.UTF_8),
            Document.Operation.UPSERT, Map.of(), Map.of())), null, false, DocumentExceptionAllowlist.empty()).block();
        client.sendBulkRequestRaw("idx", List.of(new Document("a", "{}".getBytes(StandardCharsets.UTF_8),
            Document.Operation.UPSERT, Map.of(), Map.of())), null, false, DocumentExceptionAllowlist.empty()).block();

        var names = context.inMemoryInstrumentationBundle.getFinishedMetrics().stream()
            .map(MetricData::getName).collect(Collectors.toSet());
        Assertions.assertTrue(names.containsAll(Set.of(
            "bulkBatchPreparationDuration",
            "bulkRequestAssemblyDuration",
            "bulkRequestCompressionDuration",
            "bulkRequestStartGap",
            "bulkResponseClassificationDuration"
        )), "reported: " + names);
    }

    @Test
    void reportsConfiguredLimitAndWorkerUtilizationBand() {
        var context = DocumentMigrationTestContext.factory().withAllTracking();
        context.makeBulkFlowInstruments(null, new AdaptiveLimit(10));

        var metrics = context.inMemoryInstrumentationBundle.getFinishedMetrics();
        var configuredLimit = metrics.stream()
            .filter(m -> m.getName().equals("bulkLimitMax"))
            .findFirst()
            .orElseThrow();
        Assertions.assertEquals(10.0,
            configuredLimit.getDoubleGaugeData().getPoints().iterator().next().getValue(), 1e-9);

        var utilization = metrics.stream()
            .filter(m -> m.getName().equals("bulkWorkersByLimitUtilization"))
            .findFirst()
            .orElseThrow();
        var range = AttributeKey.stringKey("range");
        var byRange = utilization.getLongGaugeData().getPoints().stream()
            .collect(Collectors.toMap(p -> p.getAttributes().get(range), p -> p.getValue()));
        Assertions.assertEquals(Map.of(
            "0-24%", 1L,
            "25-49%", 0L,
            "50-74%", 0L,
            "75-99%", 0L,
            "100%", 0L
        ), byRange);
    }

    @Test
    void aSplitAndAGiveUpAreCounted() {
        var context = DocumentMigrationTestContext.factory().withAllTracking();
        var restClient = mock(RestClient.class);
        var connection = mock(ConnectionContext.class);
        when(connection.getUri()).thenReturn(URI.create("http://localhost/"));
        when(restClient.getConnectionContext()).thenReturn(connection);
        when(restClient.postAsyncBytes(any(), any(), any(), any())).thenAnswer(invocation -> {
            List<String> ids = idsIn(invocation.getArgument(1));
            return Mono.just(ids.contains("broken") ? new HttpResponse(500, "", Map.of(), "{}") : created(ids.get(0)));
        });
        var client = new OpenSearchClient_OS_2_11(restClient, new FailedRequestsLogger(), Version.fromString("OS 2.11"),
            CompressionMode.UNCOMPRESSED, 100, 10_000_000);
        client.setBulkFlowMetrics(context.makeBulkFlowInstruments(null, null));

        client.sendBulkRequestRaw("idx", List.of("e", "broken").stream().map(id -> new Document(id,
                "{}".getBytes(StandardCharsets.UTF_8), Document.Operation.UPSERT, Map.of(), Map.of())).toList(),
            null, false, DocumentExceptionAllowlist.empty()).onErrorResume(e -> Mono.empty())
            .block(Duration.ofSeconds(120));

        var names = context.inMemoryInstrumentationBundle.getFinishedMetrics().stream().map(MetricData::getName)
            .collect(Collectors.toSet());
        Assertions.assertTrue(names.contains(BulkFlowInstruments.BULK_BATCH_SPLITS), "reported: " + names);
        Assertions.assertTrue(names.contains(BulkFlowInstruments.BULK_DOCS_GAVE_UP), "reported: " + names);
    }

    @Test
    void aQuietPeriodResetIsCounted() {
        var context = DocumentMigrationTestContext.factory().withAllTracking();
        var holder = new AdaptiveLimit[1];
        StepVerifier.withVirtualTime(() -> Mono.fromCallable(() -> {
                holder[0] = new AdaptiveLimit(4, () -> 0.0);
                holder[0].setMetrics(context.makeBulkFlowInstruments(null, null));
                holder[0].acquire().block().release(AdaptiveLimit.Signal.THROTTLED_429);
                return holder[0];
            }))
            .expectNextCount(1)
            .thenAwait(AdaptiveLimit.QUIET_PERIOD)
            .then(() -> holder[0].acquire().block().release(AdaptiveLimit.Signal.SUCCESS))
            .verifyComplete();

        var names = context.inMemoryInstrumentationBundle.getFinishedMetrics().stream().map(MetricData::getName)
            .collect(Collectors.toSet());
        Assertions.assertTrue(names.contains(BulkFlowInstruments.BULK_LIMIT_QUIET_RESETS), "reported: " + names);
    }

}
