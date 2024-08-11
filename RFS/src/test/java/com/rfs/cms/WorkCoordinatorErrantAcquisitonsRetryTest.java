package com.rfs.cms;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.opensearch.migrations.testutils.HttpRequestFirstLine;
import org.opensearch.migrations.testutils.SimpleHttpResponse;
import org.opensearch.migrations.testutils.SimpleNettyHttpServer;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;
import org.opensearch.migrations.workcoordination.tracing.WorkCoordinationTestContext;

import com.rfs.common.http.ConnectionContextTestParams;
import lombok.NonNull;

public class WorkCoordinatorErrantAcquisitonsRetryTest {

    private static final String UPDATE_BY_QUERY_RESPONSE_BODY = "" +
        "{\n" +
        "  \"total\": 1,\n" +
        "  \"updated\": 1,\n" +
        "  \"deleted\": 0,\n" +
        "  \"version_conflicts\": 0,\n" +
        "  \"noops\": 0,\n" +
        "  \"failures\": []\n" +
        "}";

    private static final String GET_NO_RESULTS_ASSIGNED_WORK_BODY = "" +
        "{\n" +
        "  \"_shards\": {\n" +
        "    \"total\": 0,\n" +
        "    \"successful\": 1,\n" +
        "    \"skipped\": 0,\n" +
        "    \"failed\": 0\n" +
        "  },\n" +
        "  \"hits\": {\n" +
        "    \"total\": {\n" +
        "      \"value\": 0,\n" +
        "      \"relation\": \"eq\"\n" +
        "    }\n" +
        "  }\n" +
        "}";

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

    @ParameterizedTest
    @ValueSource(classes = { OpenSearchWorkCoordinator.AssignedWorkDocumentNotFoundException.class,
        OpenSearchWorkCoordinator.MalformedAssignedWorkDocumentException.class })
    public void testSecondPhaseLeaseAcquisitionFailureKeepsRetrying(Class exceptionClassToTest) throws Exception {
        var pathToCounts = new PathCounts();
        String searchResultBody;
        if (exceptionClassToTest.equals(OpenSearchWorkCoordinator.AssignedWorkDocumentNotFoundException.class)) {
            searchResultBody = GET_NO_RESULTS_ASSIGNED_WORK_BODY;
        } else if (exceptionClassToTest.equals(OpenSearchWorkCoordinator.MalformedAssignedWorkDocumentException.class)) {
            searchResultBody = GET_MALFORMED_ASSIGNED_WORK_BODY;
        } else {
            throw new IllegalArgumentException("unknown class: " + exceptionClassToTest);
        }

        try (SimpleNettyHttpServer testServer = SimpleNettyHttpServer.makeServer(false, null,
            getCountingResponseMaker(pathToCounts, searchResultBody)))
        {
            var client = new CoordinateWorkHttpClient(ConnectionContextTestParams.builder()
                .host("http://localhost:" + testServer.port)
                .build()
                .toConnectionContext());
            var startingLeaseDuration = Duration.ofSeconds(1);
            var rootContext = WorkCoordinationTestContext.factory().withAllTracking();
            try (var workCoordinator = new OpenSearchWorkCoordinator(client, 2, "testWorker")) {
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
        Assertions.assertEquals(1, pathToCounts.refreshes);
    }

    @Test
    public void doubledLeaseIntervalCausesExtraRetry() throws Exception {
        var pathToCounts = new PathCounts();
        try (SimpleNettyHttpServer testServer = SimpleNettyHttpServer.makeServer(false, null,
            getCountingResponseMaker(pathToCounts, GET_MALFORMED_ASSIGNED_WORK_BODY)))
        {
            var client = new CoordinateWorkHttpClient(ConnectionContextTestParams.builder()
                .host("http://localhost:" + testServer.port)
                .build()
                .toConnectionContext());
            var startingLeaseDuration = Duration.ofSeconds(1);
            var rootContext = WorkCoordinationTestContext.factory().withAllTracking();
            try (var wc = new OpenSearchWorkCoordinator(client, 2, "testWorker")) {
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

    private static class PathCounts {
        int updates;
        int searches;
        int refreshes;
        int unknowns;

        void reset() {
            updates = searches = refreshes = unknowns = 0;
        }
    }

    @NonNull
    private static Function<HttpRequestFirstLine, SimpleHttpResponse>
    getCountingResponseMaker(PathCounts pathToCountMap, String searchResponse) {
        return httpRequestFirstLine -> {
            final var uriPath = httpRequestFirstLine.path().getPath();
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
                return makeResponse(200, "OK",
                    searchResponse.getBytes(StandardCharsets.UTF_8));
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
