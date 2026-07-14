package org.opensearch.migrations.bulkload.workcoordination;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.AbstractMap;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.function.Function;
import java.util.stream.Stream;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.SupportedClusters;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer.ContainerVersion;
import org.opensearch.migrations.bulkload.workcoordination.OpenSearchWorkCoordinator.DocumentModificationResult;
import org.opensearch.migrations.bulkload.workcoordination.OpenSearchWorkCoordinator.UnexpectedWorkCoordinationResponseException;
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
    public static final String CLOCK_DRIFT_REASON =
        "The current times indicated between the client and server are too different.";
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

    public static class SequenceMockHttpClient implements AbstractedHttpClient {
        Queue<AbstractHttpResponse> responses;
        int requestCount;

        public SequenceMockHttpClient(AbstractHttpResponse... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public AbstractHttpResponse makeRequest(String method, String path, Map<String, String> headers, String payload) {
            requestCount++;
            if (responses.size() > 1) {
                return responses.remove();
            }
            return responses.peek();
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

    private static TestResponse getClockDriftResponse() {
        return new TestResponse(
            400,
            "Bad Request",
            "{\"error\":{\"type\":\"illegal_argument_exception\",\"reason\":\"failed to execute script\","
                + "\"caused_by\":{\"type\":\"script_exception\",\"reason\":\"runtime error\","
                + "\"caused_by\":{\"type\":\"illegal_argument_exception\",\"reason\":\""
                + CLOCK_DRIFT_REASON
                + "\"}}},\"status\":400}"
        );
    }

    private static TestResponse getCreatedResponse() {
        return new TestResponse(
            201,
            "Created",
            "{\"" + OpenSearchWorkCoordinator.RESULT_OPENSSEARCH_FIELD_NAME + "\": \"created\"}"
        );
    }

    @ParameterizedTest
    @MethodSource("provideTestedVersions")
    public void testWhenGetResultWithMissingPayloadThenLoggedAndRethrown(Version version) throws Exception {
        var factory = new WorkCoordinatorFactory(version);
        var response = new TestResponse(500, "Internal Server Error", (byte[]) null);
        try (var workCoordinator = factory.get(new MockHttpClient(response), 2, "testWorker");
             var closeableLogSetup = new CloseableLogSetup(workCoordinator.getLoggerName()))
        {
            var exception = Assertions.assertThrows(
                UnexpectedWorkCoordinationResponseException.class,
                () -> workCoordinator.getResult(response)
            );
            Assertions.assertTrue(exception.getMessage().contains("did not contain a payload"));
            Assertions.assertTrue(exception.getMessage().contains("500 Internal Server Error"));
            Assertions.assertNull(exception.getCause());

            log.atDebug().setMessage("Logged events: {}").addArgument(closeableLogSetup::getLogEvents).log();
            Assertions.assertTrue(closeableLogSetup.getLogEvents().stream()
                .anyMatch(e -> e.contains("EXCEPTION: while trying to display response bytes")));
        }
    }

    @ParameterizedTest
    @MethodSource("provideTestedVersions")
    public void testWhenGetResultWithMalformedJsonThenWrapped(Version version) throws Exception {
        var factory = new WorkCoordinatorFactory(version);
        var response = new TestResponse(200, "OK", "{not-json");
        try (var workCoordinator = factory.get(new MockHttpClient(response), 2, "testWorker")) {
            var exception = Assertions.assertThrows(
                UnexpectedWorkCoordinationResponseException.class,
                () -> workCoordinator.getResult(response)
            );
            Assertions.assertTrue(exception.getMessage().contains("not parseable JSON"));
            Assertions.assertTrue(exception.getMessage().contains("200 OK"));
            Assertions.assertInstanceOf(IOException.class, exception.getCause());
        }
    }

    @ParameterizedTest
    @MethodSource("provideTestedVersions")
    public void testWhenGetResultWithUnexpectedResultFieldThenWrapped(Version version) throws Exception {
        var factory = new WorkCoordinatorFactory(version);
        var response = new TestResponse(
            200,
            "OK",
            "{\"" + OpenSearchWorkCoordinator.RESULT_OPENSSEARCH_FIELD_NAME + "\": \"" + THROTTLE_RESULT_VALUE + "\"}"
        );
        try (var workCoordinator = factory.get(new MockHttpClient(response), 2, "testWorker")) {
            var exception = Assertions.assertThrows(
                UnexpectedWorkCoordinationResponseException.class,
                () -> workCoordinator.getResult(response)
            );
            Assertions.assertTrue(exception.getMessage().contains("did not contain an expected result field"));
            Assertions.assertTrue(exception.getMessage().contains(THROTTLE_RESULT_VALUE));
            Assertions.assertNotNull(exception.getCause());
        }
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
            var exception = Assertions.assertThrows(
                UnexpectedWorkCoordinationResponseException.class,
                () -> workCoordinator.getResult(response)
            );
            Assertions.assertTrue(exception.getMessage().contains("429 THROTTLED"));
            Assertions.assertFalse(exception.getMessage().contains("Unknown result"));
            log.atDebug().setMessage("Logged events: {}").addArgument(closeableLogSetup::getLogEvents).log();
            Assertions.assertTrue(closeableLogSetup.getLogEvents().stream().anyMatch(e -> e.contains(THROTTLE_RESULT_VALUE)));
        }
    }

    @ParameterizedTest
    @MethodSource("provideTestedVersions")
    public void testWhenGetResultWithClockDriftThenReasonIsSpecific(Version version) throws Exception {
        var factory = new WorkCoordinatorFactory(version);
        var response = getClockDriftResponse();
        try (var workCoordinator = factory.get(new MockHttpClient(response), 2, "testWorker")) {
            var exception = Assertions.assertThrows(
                UnexpectedWorkCoordinationResponseException.class,
                () -> workCoordinator.getResult(response)
            );
            Assertions.assertTrue(exception.getMessage().contains(CLOCK_DRIFT_REASON));
            Assertions.assertFalse(exception.getMessage().contains("Unknown result null"));
        }
    }

    @ParameterizedTest
    @MethodSource("provideTestedVersions")
    public void testCreateOrUpdateLeaseRetriesUnexpectedCoordinatorResponse(Version version) throws Exception {
        var factory = new WorkCoordinatorFactory(version);
        var client = new SequenceMockHttpClient(getClockDriftResponse(), getCreatedResponse());
        var workItem = new IWorkCoordinator.WorkItemAndDuration.WorkItem("item", 0, 0L).toString();

        try (var workCoordinator = factory.get(client, 2, "testWorker")) {
            var result = workCoordinator.createOrUpdateLeaseForWorkItem(workItem, Duration.ofMinutes(5), () -> null);
            Assertions.assertInstanceOf(IWorkCoordinator.WorkItemAndDuration.class, result);
            Assertions.assertEquals(2, client.requestCount);
        }
    }

    @ParameterizedTest
    @MethodSource("provideTestedVersions")
    public void testCreateUnassignedWorkItemRetriesUnexpectedCoordinatorResponse(Version version) throws Exception {
        var factory = new WorkCoordinatorFactory(version);
        var client = new SequenceMockHttpClient(getClockDriftResponse(), getCreatedResponse());
        var workItem = new IWorkCoordinator.WorkItemAndDuration.WorkItem("item", 0, 0L).toString();

        try (var workCoordinator = factory.get(client, 2, "testWorker")) {
            Assertions.assertTrue(workCoordinator.createUnassignedWorkItem(workItem, () -> null));
            Assertions.assertEquals(2, client.requestCount);
        }
    }

    @ParameterizedTest
    @MethodSource("provideTestedVersions")
    public void testCreateUnassignedWorkItemConvertsInterruptedRetryToInterruptedIOException(Version version) throws Exception {
        var factory = new WorkCoordinatorFactory(version);
        var client = new MockHttpClient(getClockDriftResponse());
        var workItem = new IWorkCoordinator.WorkItemAndDuration.WorkItem("item", 0, 0L).toString();

        try (var workCoordinator = factory.get(client, 2, "testWorker")) {
            Thread.currentThread().interrupt();
            var exception = Assertions.assertThrows(
                InterruptedIOException.class,
                () -> workCoordinator.createUnassignedWorkItem(workItem, () -> null)
            );
            Assertions.assertTrue(exception.getMessage().contains("Interrupted while retrying createUnassignedWorkItem"));
            Assertions.assertInstanceOf(InterruptedException.class, exception.getCause());
            Assertions.assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    static Stream<Arguments> makeConsumers() {
        var workItem = new IWorkCoordinator.WorkItemAndDuration.WorkItem("item", 0, 0L).toString();


        var functions = List.<Function<IWorkCoordinator, Exception>>of(
            wc -> Assertions.assertThrows(IOException.class,
                () -> wc.createUnassignedWorkItem(workItem, () -> null)),
            wc -> Assertions.assertThrows(IOException.class,
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
            var exception = worker.apply(workCoordinator);
            Assertions.assertTrue(exception.getMessage().contains("failed after"));
            Assertions.assertFalse(exception.getMessage().contains("Unknown result"));
            log.atDebug().setMessage("Logged events: {}").addArgument(()->closeableLogSetup.getLogEvents()).log();
            var logEvents = closeableLogSetup.getLogEvents();
            Assertions.assertTrue(logEvents.stream().anyMatch(e -> e.contains(THROTTLE_RESULT_VALUE)));
        }
    }
}
