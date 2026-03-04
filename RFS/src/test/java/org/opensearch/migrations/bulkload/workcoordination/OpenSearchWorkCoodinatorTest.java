package org.opensearch.migrations.bulkload.workcoordination;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.SupportedClusters;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer.ContainerVersion;
import org.opensearch.migrations.bulkload.workcoordination.OpenSearchWorkCoordinator.DocumentModificationResult;
import org.opensearch.migrations.testutils.CloseableLogSetup;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.shaded.com.fasterxml.jackson.core.JsonProcessingException;
import org.testcontainers.shaded.com.fasterxml.jackson.databind.ObjectMapper;

@Slf4j
class OpenSearchWorkCoodinatorTest {

    public static final String THROTTLE_RESULT_VALUE = "slow your roll, dude";
    public static List<Version> testedVersions = SupportedClusters.supportedTargets(true)
                    .stream()
                    .map(ContainerVersion::getVersion)
                    .toList();

    static Stream<Arguments> provideTestedVersions() {
        return testedVersions.stream().map(Arguments::of);
    }

    @AllArgsConstructor
    public static class MockHttpClient implements AbstractedHttpClient {
        AbstractHttpResponse response;

        @Override
        public AbstractHttpResponse makeRequest(String method, String path, Map<String, String> headers, String payload) {
            return response;
        }
    }

    @AllArgsConstructor
    @Getter
    public static class TestResponse implements AbstractedHttpClient.AbstractHttpResponse {
        int statusCode;
        String statusText;
        byte[] payloadBytes;

        public TestResponse(int statusCode, String statusText, String payloadString) {
            this(statusCode, statusText, payloadString.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public Stream<Map.Entry<String, String>> getHeaders() {
            return Stream.of(new AbstractMap.SimpleEntry<>("Content-Type", "application/json"));
        }
    }

    static Stream<Arguments> provideGetResultTestArgs() {
        List<Arguments> staticArguments = List.of(
            Arguments.of(DocumentModificationResult.IGNORED,
                "{\"" + OpenSearchWorkCoordinator.RESULT_OPENSSEARCH_FIELD_NAME + "\": \"noop\"}"
            ),
            Arguments.of(DocumentModificationResult.CREATED,
                "{\"" + OpenSearchWorkCoordinator.RESULT_OPENSSEARCH_FIELD_NAME + "\": \"created\"}"
            ),
            Arguments.of(DocumentModificationResult.UPDATED,
                "{\"" + OpenSearchWorkCoordinator.RESULT_OPENSSEARCH_FIELD_NAME + "\": \""+ OpenSearchWorkCoordinator.UPDATED_COUNT_FIELD_NAME +"\"}"
            )
        );

        return testedVersions.stream()
            .flatMap(version -> staticArguments.stream()
                .map(staticArg -> Arguments.of(version, staticArg.get()[0], staticArg.get()[1]))
            );
    }

    @ParameterizedTest
    @MethodSource("provideGetResultTestArgs")
    public void testWhenGetResult(Version version, DocumentModificationResult expectedResult, String responsePayload) throws Exception {
        var factory = new WorkCoordinatorFactory(version);
        var response = new TestResponse(200, "ok", responsePayload);
        try (var workCoordinator = factory.get(new MockHttpClient(response), 2, "testWorker")) {
            var result = workCoordinator.getResult(response);
            Assertions.assertEquals(expectedResult, result);
        }
    }

    @ParameterizedTest
    @MethodSource("provideTestedVersions")
    public void testWhenGetResultAndConflictThenIgnored(Version version) throws Exception {
        var factory = new WorkCoordinatorFactory(version);
        var response = new TestResponse(409, "conflict", "");
        try (var workCoordinator = factory.get(new MockHttpClient(response), 2, "testWorker")) {
            var result = workCoordinator.getResult(response);
            Assertions.assertEquals(DocumentModificationResult.IGNORED, result);
        }
    }

    private static TestResponse getThrottleResponse() throws JsonProcessingException {
        var resultJson = Map.of(OpenSearchWorkCoordinator.RESULT_OPENSSEARCH_FIELD_NAME, THROTTLE_RESULT_VALUE);
        return new TestResponse(429, "THROTTLED", new ObjectMapper().writeValueAsString(resultJson));
    }

    @ParameterizedTest
    @MethodSource("provideTestedVersions")
    public void testWhenGetResultAndErrorThenLogged(Version version) throws Exception {
        var factory = new WorkCoordinatorFactory(version);
        var response = getThrottleResponse();
        MockHttpClient client = new MockHttpClient(response);

        try (var workCoordinator = factory.get(client, 2, "testWorker");
             var closeableLogSetup = new CloseableLogSetup(workCoordinator.getLoggerName()))
        {
            log.atInfo().log(workCoordinator.getClass().getName());
            Assertions.assertThrows(IllegalArgumentException.class, () -> workCoordinator.getResult(response));
            log.atDebug().setMessage("Logged events: {}").addArgument(closeableLogSetup::getLogEvents).log();
            Assertions.assertTrue(closeableLogSetup.getLogEvents().stream().anyMatch(e -> e.contains(THROTTLE_RESULT_VALUE)));
        }
    }

    static Stream<Arguments> makeConsumers() {
        var workItem = new IWorkCoordinator.WorkItemAndDuration.WorkItem("item", 0, 0L).toString();


        var functions = List.<Function<IWorkCoordinator, Exception>>of(
            wc -> Assertions.assertThrows(Exception.class,
                () -> wc.createUnassignedWorkItem(workItem, () -> null)),
            wc -> Assertions.assertThrows(Exception.class,
                () -> wc.createOrUpdateLeaseForWorkItem(workItem, Duration.ZERO, () -> null))
        );
    
        return testedVersions.stream()
            .flatMap(version -> functions.stream()
                .map(function -> Arguments.of(version, function)));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("makeConsumers")
    public void testWhenInvokedWithHttpErrorThenLogged(Version version, Function<IWorkCoordinator, Exception> worker) throws Exception {
        var factory = new WorkCoordinatorFactory(version);
        try (var workCoordinator = factory.get(new MockHttpClient(getThrottleResponse()), 2, "t");
             var closeableLogSetup = new CloseableLogSetup(workCoordinator.getLoggerName()))
        {
            worker.apply(workCoordinator);
            log.atDebug().setMessage("Logged events: {}").addArgument(()->closeableLogSetup.getLogEvents()).log();
            var logEvents = closeableLogSetup.getLogEvents();
            Assertions.assertTrue(logEvents.stream().anyMatch(e -> e.contains(THROTTLE_RESULT_VALUE)));
        }
    }
}
