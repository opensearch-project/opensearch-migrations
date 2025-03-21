package org.opensearch.migrations.bulkload.workcoordination;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.testutils.HttpRequest;
import org.opensearch.migrations.testutils.SimpleHttpResponse;
import org.opensearch.migrations.testutils.SimpleNettyHttpServer;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;
import org.opensearch.migrations.workcoordination.tracing.WorkCoordinationTestContext;

import lombok.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.MDC;

public class WorkCoordinatorErrantAcquisitonsRetryTest {
    private static final WorkCoordinatorFactory factory = new WorkCoordinatorFactory(Version.fromString("OS 2.11"));

    private static final String UPDATE_BY_QUERY_RESPONSE_BODY = "" +
        "{\n" +
        "  \"total\": 1,\n" +
        "  \"updated\": 1,\n" +
        "  \"deleted\": 0,\n" +
        "  \"version_conflicts\": 0,\n" +
        "  \"noops\": 0,\n" +
        "  \"failures\": []\n" +
        "}";

    private static final String GET_NO_RESULTS_ASSIGNED_WORK_BODY = getAssignedWorkBody(0);
    private static final String GET_TWO_RESULTS_ASSIGNED_WORK_BODY = getAssignedWorkBody(2);

    private static String getAssignedWorkBody(int numDocs) {
        return
            "{\n" +
            "  \"_shards\": {\n" +
            "    \"total\": 0,\n" +
            "    \"successful\": 1,\n" +
            "    \"skipped\": 0,\n" +
            "    \"failed\": 0\n" +
            "  },\n" +
            "  \"hits\": {\n" +
            "    \"total\": {\n" +
            "      \"value\": " + numDocs + ",\n" +
            "      \"relation\": \"eq\"\n" +
            "    }\n" +
            "  }\n" +
            "}";
    }

    private static final String GET_MALFORMED_ASSIGNED_WORK_BODY = "" +
        "{\n" +
        "  \"_shards\": {\n" +
        "    \"total\": 1,\n" +
        "    \"successful\": 1,\n" +
        "    \"skipped\": 0,\n" +
        "    \"failed\": 0\n" +
        "  },\n" +
        "  \"hits\": {\n" +
        "    \"total\": {\n" +
        "      \"value\": 1,\n" +
        "      \"relation\": \"eq\"\n" +
        "    },\n" +
//        "    \"max_score\": 1.0,\n" +
        "    \"hits\": [\n" +
        "      {\n" +
        "        \"_index\": \"" + OpenSearchWorkCoordinator.INDEX_NAME + "\",\n" +
        "        \"_id\": \"SAMPLE_WORK_ITEM_DOC_ID\",\n" +
        "        \"_score\": 1.0,\n" +
        "        \"_source\": {\n" +
        "        }\n" +
        "      }\n" +
        "    ]\n" +
        "  }\n" +
        "}";
    public static final String ACQUIRE_NEXT_WORK_ITEM_EXCEPTION_COUNT_METRIC_NAME = "acquireNextWorkItemExceptionCount";
    public static final String TEST_WORKER_ID = "testWorker";

    @BeforeAll
    public static void initialize() {
        MDC.put("workerId", TEST_WORKER_ID); // I don't see a need to clean this up since we're in main
    }

    private static Stream<Arguments> makeArgs() {
        return Stream.of(
            Arguments.of(OpenSearchWorkCoordinator.AssignedWorkDocumentNotFoundException.class,
                getCountingResponseMakerWithSearchBody(GET_NO_RESULTS_ASSIGNED_WORK_BODY)),
            Arguments.of(OpenSearchWorkCoordinator.AssignedWorkDocumentNotFoundException.class,
                getCountingResponseMaker(makeResponse(429, "Too Many Requests",
                    "{}".getBytes(StandardCharsets.UTF_8)))),

            Arguments.of(OpenSearchWorkCoordinator.MalformedAssignedWorkDocumentException.class,
                getCountingResponseMakerWithSearchBody(GET_TWO_RESULTS_ASSIGNED_WORK_BODY)),
            Arguments.of(OpenSearchWorkCoordinator.MalformedAssignedWorkDocumentException.class,
                getCountingResponseMakerWithSearchBody(GET_MALFORMED_ASSIGNED_WORK_BODY))
        );
    }

    @ParameterizedTest
    @MethodSource(value = "makeArgs")
    public void testSecondPhaseLeaseAcquisitionFailureKeepsRetrying(
        Class exceptionClassToTest,
        Function<PathCounts, Function<HttpRequest, SimpleHttpResponse>> responseFactory)
        throws Exception
    {
        var pathToCounts = new PathCounts();

        try (SimpleNettyHttpServer testServer = SimpleNettyHttpServer.makeServer(false, null,
            responseFactory.apply(pathToCounts)))
        {
            var client = new CoordinateWorkHttpClient(ConnectionContextTestParams.builder()
                .host("http://localhost:" + testServer.port)
                .build()
                .toConnectionContext());
            var startingLeaseDuration = Duration.ofSeconds(1);
            var rootContext = WorkCoordinationTestContext.factory().withAllTracking();
            try (var workCoordinator = factory.get(client, 2, TEST_WORKER_ID)) {
                var e = Assertions.assertThrows(OpenSearchWorkCoordinator.RetriesExceededException.class,
                    () -> workCoordinator.acquireNextWorkItem(startingLeaseDuration, rootContext::createAcquireNextItemContext));
                validate(pathToCounts, exceptionClassToTest, e);

                var metrics = rootContext.inMemoryInstrumentationBundle.getFinishedMetrics();
                Assertions.assertEquals(e.retries + 1, // we don't retry on the final exception
                    InMemoryInstrumentationBundle.getMetricValueOrZero(metrics,
                        ACQUIRE_NEXT_WORK_ITEM_EXCEPTION_COUNT_METRIC_NAME));
            }
        }
    }

    private static <E extends Exception> void validate(PathCounts pathToCounts,
                                                       Class<E> expectedCauseClass,
                                                       OpenSearchWorkCoordinator.RetriesExceededException e) throws Exception {
        Assertions.assertEquals(0, pathToCounts.unknowns);
        Assertions.assertEquals(1, pathToCounts.updates);
        Assertions.assertEquals(pathToCounts.searches, e.retries+1);
        Assertions.assertInstanceOf(expectedCauseClass, e.getCause());
        Assertions.assertEquals(0, pathToCounts.refreshes);
    }

    @Test
    public void doubledLeaseIntervalCausesExtraRetry() throws Exception {
        var pathToCounts = new PathCounts();
        try (SimpleNettyHttpServer testServer = SimpleNettyHttpServer.makeServer(false, null,
            getCountingResponseMakerWithSearchBody(GET_MALFORMED_ASSIGNED_WORK_BODY).apply(pathToCounts)))
        {
            var client = new CoordinateWorkHttpClient(ConnectionContextTestParams.builder()
                .host("http://localhost:" + testServer.port)
                .build()
                .toConnectionContext());
            var startingLeaseDuration = Duration.ofSeconds(1);
            var rootContext = WorkCoordinationTestContext.factory().withAllTracking();
            try (var wc = factory.get(client, 2, TEST_WORKER_ID)) {
                var e1 = Assertions.assertThrows(OpenSearchWorkCoordinator.RetriesExceededException.class,
                    () -> wc.acquireNextWorkItem(startingLeaseDuration, rootContext::createAcquireNextItemContext));
                validate(pathToCounts, OpenSearchWorkCoordinator.MalformedAssignedWorkDocumentException.class, e1);

                pathToCounts.reset();
                var e2 = Assertions.assertThrows(OpenSearchWorkCoordinator.RetriesExceededException.class,
                    () -> wc.acquireNextWorkItem(startingLeaseDuration.multipliedBy(3), rootContext::createAcquireNextItemContext));
                validate(pathToCounts, OpenSearchWorkCoordinator.MalformedAssignedWorkDocumentException.class, e2);
                Assertions.assertTrue(e1.retries < e2.retries);
            }
        }
    }

    public static class PathCounts {
        int updates;
        int searches;
        int refreshes;
        int unknowns;

        void reset() {
            updates = searches = refreshes = unknowns = 0;
        }
    }

    @NonNull
    private static Function<PathCounts, Function<HttpRequest, SimpleHttpResponse>>
    getCountingResponseMakerWithSearchBody(String searchResponse) {
        var payloadBytes = searchResponse.getBytes(StandardCharsets.UTF_8);
        return pathCounts -> getCountingResponseMaker(pathCounts, makeResponse(200, "OK", payloadBytes));
    }

    @NonNull
    private static Function<PathCounts, Function<HttpRequest, SimpleHttpResponse>>
    getCountingResponseMaker(SimpleHttpResponse searchResponse) {
        return pathCounts -> getCountingResponseMaker(pathCounts, searchResponse);
    }

    @NonNull
    private static Function<HttpRequest, SimpleHttpResponse>
    getCountingResponseMaker(PathCounts pathToCountMap, SimpleHttpResponse searchResponse) {
        return httpRequestFirstLine -> {
            final var uriPath = httpRequestFirstLine.getPath().getPath();
            if (uriPath.startsWith("/" + OpenSearchWorkCoordinator.INDEX_NAME + "/_refresh")) {
                ++pathToCountMap.refreshes;
                return makeResponse(200, "OK",
                    "".getBytes(StandardCharsets.UTF_8));
            } else if (uriPath.startsWith("/" + OpenSearchWorkCoordinator.INDEX_NAME + "/_update_by_query")) {
                ++pathToCountMap.updates;
                return makeResponse(200, "OK",
                    UPDATE_BY_QUERY_RESPONSE_BODY.getBytes(StandardCharsets.UTF_8));
            } else if (uriPath.startsWith("/" + OpenSearchWorkCoordinator.INDEX_NAME + "/_search")) {
                ++pathToCountMap.searches;
                return searchResponse;
            } else {
                ++pathToCountMap.unknowns;
                return makeResponse(404, "Not Found", new byte[0]);
            }
        };
    }

    private static SimpleHttpResponse makeResponse(int statusCode, String statusText, byte[] payloadBytes) {
        return new SimpleHttpResponse(Map.of("Content-Type", "text/plain",
            "Content-Length", "" + payloadBytes.length),
            payloadBytes, statusText, statusCode);
    }
}
