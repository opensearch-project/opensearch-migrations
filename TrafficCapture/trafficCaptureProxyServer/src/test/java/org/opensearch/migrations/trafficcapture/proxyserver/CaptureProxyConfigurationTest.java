package org.opensearch.migrations.trafficcapture.proxyserver;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.opensearch.migrations.testutils.SimpleHttpClientForTesting;
import org.opensearch.migrations.trafficcapture.proxyserver.testcontainers.CaptureProxyContainer;
import org.opensearch.migrations.trafficcapture.proxyserver.testcontainers.HttpdContainerTestBase;
import org.opensearch.migrations.trafficcapture.proxyserver.testcontainers.annotations.HttpdContainerTest;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;


@Slf4j
@HttpdContainerTest
public class CaptureProxyConfigurationTest {

    private static final HttpdContainerTestBase httpdTestBase = new HttpdContainerTestBase();
    private static final int DEFAULT_NUMBER_OF_CALLS = 3;
    private static final long PROXY_EXPECTED_MAX_LATENCY_MS = Duration.ofSeconds(1).toMillis();

    @BeforeAll
    public static void setUp() {
        httpdTestBase.start();
    }

    @AfterAll
    public static void tearDown() {
        httpdTestBase.stop();
    }

    private static void assertLessThan(long ceiling, long actual) {
        Assertions.assertTrue(
            actual < ceiling,
            () -> "Expected actual value to be less than " + ceiling + " but was " + actual + "."
        );
    }

    @Test
    public void testCaptureProxyWithNoCapturePassesRequest() {
        try (var captureProxy = new CaptureProxyContainer(httpdTestBase.getContainer())) {
            captureProxy.start();

            var latency = assertBasicCalls(captureProxy, DEFAULT_NUMBER_OF_CALLS);

            assertLessThan(PROXY_EXPECTED_MAX_LATENCY_MS, latency.toMillis());
        }
    }

    private Duration assertBasicCalls(CaptureProxyContainer proxy, int numberOfCalls) {
        return assertBasicCalls(CaptureProxyContainer.getUriFromContainer(proxy), numberOfCalls);
    }

    private Duration assertBasicCalls(String endpoint, int numberOfCalls) {
        return IntStream.range(0, numberOfCalls)
            .mapToObj(i -> assertBasicCall(endpoint))
            .reduce(Duration.ZERO, Duration::plus)
            .dividedBy(numberOfCalls);
    }

    private Duration assertBasicCall(String endpoint) {
        try (var client = new SimpleHttpClientForTesting()) {
            long startTimeNanos = System.nanoTime();
            var response = client.makeGetRequest(URI.create(endpoint), Stream.empty());
            long endTimeNanos = System.nanoTime();

            var responseBody = new String(response.payloadBytes);
            Assertions.assertNotNull(responseBody);
            Assertions.assertTrue(responseBody.toLowerCase(Locale.ROOT).contains("it works"));
            return Duration.ofNanos(endTimeNanos - startTimeNanos);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
