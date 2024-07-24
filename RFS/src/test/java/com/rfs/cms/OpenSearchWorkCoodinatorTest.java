package com.rfs.cms;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.opensearch.migrations.logging.CloseableLogSetup;

import com.rfs.cms.OpenSearchWorkCoordinator.DocumentModificationResult;

class OpenSearchWorkCoodinatorTest {

    public static class MockHttpClient implements AbstractedHttpClient {
        Supplier<AbstractHttpResponse> responseSupplier;

        public MockHttpClient(Supplier<AbstractHttpResponse> responseSupplier) {
            this.responseSupplier = responseSupplier;
        }

        @Override
        public AbstractHttpResponse makeRequest(String method, String path, Map<String, String> headers, String payload) {
            return responseSupplier.get();
        }

        @Override
        public void close() {
            // Do nothing
        }
    }

    public static class TestResponse implements AbstractedHttpClient.AbstractHttpResponse {
        @Override
        public Stream<Map.Entry<String, String>> getHeaders() {
            return Stream.of(new AbstractMap.SimpleEntry<>("Content-Type", "application/json"));
        }

        @Override
        public String getStatusText() {
            return "ok";
        }

        @Override
        public int getStatusCode() {
            return 200;
        }
    }

    static Stream<Arguments> provideGetResultTestArgs() {
        return Stream.of(
            // Noop
            Arguments.of(
                (Supplier<AbstractedHttpClient.AbstractHttpResponse>) () -> new TestResponse() {
                    @Override
                    public InputStream getPayloadStream() {
                        String payload = "{\"" + OpenSearchWorkCoordinator.RESULT_OPENSSEARCH_FIELD_NAME + "\": \"noop\"}";
                        return new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8));
                    }
                },
                DocumentModificationResult.IGNORED
            ),
            // Created
            Arguments.of(
                (Supplier<AbstractedHttpClient.AbstractHttpResponse>) () -> new TestResponse() {
                    @Override
                    public InputStream getPayloadStream() {
                        String payload = "{\"" + OpenSearchWorkCoordinator.RESULT_OPENSSEARCH_FIELD_NAME + "\": \"created\"}";
                        return new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8));
                    }
                },
                DocumentModificationResult.CREATED
            ),
            // Updated
            Arguments.of(
                (Supplier<AbstractedHttpClient.AbstractHttpResponse>) () -> new TestResponse() {
                    @Override
                    public InputStream getPayloadStream() {
                        String payload = "{\"" + OpenSearchWorkCoordinator.RESULT_OPENSSEARCH_FIELD_NAME + "\": \""+ OpenSearchWorkCoordinator.UPDATED_COUNT_FIELD_NAME +"\"}";
                        return new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8));
                    }
                },
                DocumentModificationResult.UPDATED
            )
        );
    }

    @ParameterizedTest
    @MethodSource("provideGetResultTestArgs")
    public void testWhenGetResult(
            Supplier<AbstractedHttpClient.AbstractHttpResponse> responseSupplier,
            DocumentModificationResult expectedTesult) throws Exception {

        // Set up our test
        MockHttpClient client = new MockHttpClient(responseSupplier);

        // Run the test
        DocumentModificationResult result;
        try (var workCoordinator = new OpenSearchWorkCoordinator(client, 2, "testWorker")) {
            result = workCoordinator.getResult(responseSupplier.get());
        }

        // Verify the result
        Assertions.assertEquals(expectedTesult, result);

    }

    @Test
    public void testWhenGetResultAndConflictThenIgnored() throws Exception {
        // Set up our test
        class ConflictResponse extends TestResponse {
            @Override
            public String getStatusText() {
                return "conflict";
            }

            @Override
            public int getStatusCode() {
                return 409;
            }
        }

        Supplier<AbstractedHttpClient.AbstractHttpResponse> responseSupplier = () -> new ConflictResponse();
        MockHttpClient client = new MockHttpClient(responseSupplier);

        // Run the test
        DocumentModificationResult result;
        try (var workCoordinator = new OpenSearchWorkCoordinator(client, 2, "testWorker")) {
            result = workCoordinator.getResult(responseSupplier.get());
        }

        // Verify the result
        Assertions.assertEquals(DocumentModificationResult.IGNORED, result);

    }

    @Test
    public void testWhenGetResultAndErrorThenLogged() throws Exception {
        // Set up our test
        class ErrorResponse extends TestResponse {
            @Override
            public InputStream getPayloadStream() {
                String payload = "{\"" + OpenSearchWorkCoordinator.RESULT_OPENSSEARCH_FIELD_NAME + "\": \"slow your roll, dude\"}";
                return new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8));
            }

            @Override
            public int getStatusCode() {
                return 429;
            }
        }
        Supplier<AbstractedHttpClient.AbstractHttpResponse> responseSupplier = () -> new ErrorResponse();
        MockHttpClient client = new MockHttpClient(responseSupplier);

        // Run the test & verify
        DocumentModificationResult result;
        try (
                var workCoordinator = new OpenSearchWorkCoordinator(client, 2, "testWorker");
                var closeableLogSetup = new CloseableLogSetup(workCoordinator.getClass().getName())) {
            Assertions.assertThrows(IllegalArgumentException.class, () -> {
                workCoordinator.getResult(responseSupplier.get());
            });
            System.out.println("Logged events: " + closeableLogSetup.getLogEvents());
            Assertions.assertTrue(closeableLogSetup.getLogEvents().stream().anyMatch(e -> e.contains("slow your roll")));
        }
    }

}
