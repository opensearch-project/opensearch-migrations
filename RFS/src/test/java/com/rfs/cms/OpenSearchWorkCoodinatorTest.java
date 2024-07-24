package com.rfs.cms;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import org.opensearch.migrations.logging.CloseableLogSetup;

import com.rfs.cms.OpenSearchWorkCoordinator.DocumentModificationResult;
import lombok.SneakyThrows;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.everyItem;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenSearchWorkCoodinatorTest {

    AbstractedHttpClient mockClient = mock(AbstractedHttpClient.class);

    static Stream<Arguments> provideGetResultTestArgs() {
        return Stream.of(
            // Noop
            Arguments.of(coordinatorResponseWithBody(200, "noop"), DocumentModificationResult.IGNORED),
            // Created
            Arguments.of(coordinatorResponseWithBody(200, "created"), DocumentModificationResult.CREATED),
            // Updated
            Arguments.of(
                coordinatorResponseWithBody(200, OpenSearchWorkCoordinator.UPDATED_COUNT_FIELD_NAME),
                DocumentModificationResult.UPDATED
            )
        );
    }

    @SneakyThrows
    private static AbstractedHttpClient.AbstractHttpResponse coordinatorResponseWithBody(int code, String fieldValue) {
        var response = mock(AbstractedHttpClient.AbstractHttpResponse.class);
        when(response.getStatusCode()).thenReturn(code);
        String payload = "{\""
            + OpenSearchWorkCoordinator.RESULT_OPENSSEARCH_FIELD_NAME
            + "\": \""
            + fieldValue
            + "\"}";
        var payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        when(response.getPayloadStream()).thenReturn(new ByteArrayInputStream(payloadBytes));
        when(response.getPayloadBytes()).thenReturn(payloadBytes);
        return response;
    }

    private static AbstractedHttpClient.AbstractHttpResponse coordinatorResponseFailure(int code, String statusText) {
        var response = mock(AbstractedHttpClient.AbstractHttpResponse.class);
        when(response.getStatusCode()).thenReturn(code);
        when(response.getStatusText()).thenReturn(statusText);
        return response;
    }

    @ParameterizedTest
    @MethodSource("provideGetResultTestArgs")
    public void testWhenGetResult(
        AbstractedHttpClient.AbstractHttpResponse response,
        DocumentModificationResult expectedResult
    ) throws Exception {
        // Set up our test
        try (var workCoordinator = new OpenSearchWorkCoordinator(mockClient, 2, "testWorker")) {

            // Run the test
            var result = workCoordinator.getResult(response);

            // Verify the result
            Assertions.assertEquals(expectedResult, result);
        }
    }

    @Test
    public void testWhenGetResultAndConflictThenIgnored() throws Exception {
        // Run the test
        DocumentModificationResult result;
        try (var workCoordinator = new OpenSearchWorkCoordinator(mockClient, 2, "testWorker")) {
            result = workCoordinator.getResult(coordinatorResponseFailure(409, "conflict"));
        }

        // Verify the result
        Assertions.assertEquals(DocumentModificationResult.IGNORED, result);
    }

    @Test
    public void testWhenGetResultAndErrorThenLogged() throws Exception {
        // Set up our test
        // Run the test & verify
        try (
            var workCoordinator = new OpenSearchWorkCoordinator(mockClient, 2, "testWorker");
            var closeableLogSetup = new CloseableLogSetup(workCoordinator.getClass().getName())
        ) {
            Assertions.assertThrows(IllegalArgumentException.class, () -> {
                workCoordinator.getResult(coordinatorResponseWithBody(429, "slow your roll, dude"));
            });
            assertThat(closeableLogSetup.getLogEvents(), everyItem(containsString("slow your roll, dude")));
        }
    }
}
