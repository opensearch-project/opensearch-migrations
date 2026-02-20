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
import org.opensearch.migrations.trafficcapture.proxyserver.testcontainers.KafkaContainerTestBase;
import org.opensearch.migrations.trafficcapture.proxyserver.testcontainers.ToxiproxyContainerTestBase;
import org.opensearch.migrations.trafficcapture.proxyserver.testcontainers.annotations.HttpdContainerTest;
import org.opensearch.migrations.trafficcapture.proxyserver.testcontainers.annotations.KafkaContainerTest;
import org.opensearch.migrations.trafficcapture.proxyserver.testcontainers.annotations.ToxiproxyContainerTest;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.ThrowingConsumer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Slf4j
@Tag("isolatedTest")
@KafkaContainerTest
@HttpdContainerTest
@ToxiproxyContainerTest
public class KafkaConfigurationCaptureProxyTest {

    private static final KafkaContainerTestBase kafkaTestBase = new KafkaContainerTestBase();
    private static final HttpdContainerTestBase httpdTestBase = new HttpdContainerTestBase();
    private static final ToxiproxyContainerTestBase toxiproxyTestBase = new ToxiproxyContainerTestBase();
    private static final int DEFAULT_NUMBER_OF_CALLS = 3;
    private static final long PROXY_EXPECTED_MAX_LATENCY_MS = Duration.ofSeconds(1).toMillis();
    private Proxy kafkaProxy;
    private Proxy destinationProxy;

    @BeforeAll
    public static void setUp() {
        kafkaTestBase.start();
        httpdTestBase.start();
        toxiproxyTestBase.start();
    }

    @AfterAll
    public static void tearDown() {
        kafkaTestBase.stop();
        httpdTestBase.stop();
        toxiproxyTestBase.stop();
    }

    private static void assertLessThan(long ceiling, long actual) {
        Assertions.assertTrue(
            actual < ceiling,
            () -> "Expected actual value to be less than " + ceiling + " but was " + actual + "."
        );
    }

    @BeforeEach
    public void setUpTest() {
        kafkaProxy = toxiproxyTestBase.getProxy(kafkaTestBase.getContainer());
        destinationProxy = toxiproxyTestBase.getProxy(httpdTestBase.getContainer());
    }

    @AfterEach
    public void tearDownTest() {
        toxiproxyTestBase.deleteProxy(kafkaProxy);
        toxiproxyTestBase.deleteProxy(destinationProxy);
    }

    @ParameterizedTest
    @EnumSource(FailureMode.class)
    public void testCaptureProxyWithKafkaImpairedBeforeStart(FailureMode failureMode) {
        try (
            var captureProxy = new CaptureProxyContainer(
                toxiproxyTestBase.getProxyUrlHttp(destinationProxy),
                toxiproxyTestBase.getProxyUrlHttp(kafkaProxy)
            )
        ) {
            failureMode.apply(kafkaProxy);

            captureProxy.start();

            var latency = assertBasicCalls(captureProxy, DEFAULT_NUMBER_OF_CALLS);

            assertLessThan(PROXY_EXPECTED_MAX_LATENCY_MS, latency.toMillis());
        }
    }

    @ParameterizedTest
    @EnumSource(FailureMode.class)
    public void testCaptureProxyWithKafkaImpairedAfterStart(FailureMode failureMode) {
        try (
            var captureProxy = new CaptureProxyContainer(
                toxiproxyTestBase.getProxyUrlHttp(destinationProxy),
                toxiproxyTestBase.getProxyUrlHttp(kafkaProxy)
            )
        ) {
            captureProxy.start();

            failureMode.apply(kafkaProxy);

            var latency = assertBasicCalls(captureProxy, DEFAULT_NUMBER_OF_CALLS);

            assertLessThan(PROXY_EXPECTED_MAX_LATENCY_MS, latency.toMillis());
        }
    }

    @ParameterizedTest
    @EnumSource(FailureMode.class)
    public void testCaptureProxyWithKafkaImpairedDoesNotAffectRequest_proxysRequest(FailureMode failureMode) {
        try (
            var captureProxy = new CaptureProxyContainer(
                toxiproxyTestBase.getProxyUrlHttp(destinationProxy),
                toxiproxyTestBase.getProxyUrlHttp(kafkaProxy)
            )
        ) {
            captureProxy.start();
            final int numberOfTests = 20;

            // Performance is different for first few calls so throw them away
            assertBasicCalls(captureProxy, 3);

            var averageBaselineDuration = assertBasicCalls(captureProxy, numberOfTests);

            failureMode.apply(kafkaProxy);

            // Calculate average duration of impaired calls
            var averageImpairedDuration = assertBasicCalls(captureProxy, numberOfTests);

            long acceptableDifference = Duration.ofMillis(25).toMillis();

            log.info(
                "Baseline Duration: {}ms, Impaired Duration: {}ms",
                averageBaselineDuration.toMillis(),
                averageImpairedDuration.toMillis()
            );

            assertEquals(
                averageBaselineDuration.toMillis(),
                averageImpairedDuration.toMillis(),
                acceptableDifference,
                "The average durations are not close enough"
            );
        }
    }

    @Test
    public void testCaptureProxyLatencyAddition() {
        try (
            var captureProxy = new CaptureProxyContainer(
                toxiproxyTestBase.getProxyUrlHttp(destinationProxy),
                toxiproxyTestBase.getProxyUrlHttp(kafkaProxy)
            )
        ) {
            captureProxy.start();
            final int numberOfTests = 25;

            // Performance is different for first few calls so throw them away
            assertBasicCalls(captureProxy, 3);

            var averageRequestDurationWithProxy = assertBasicCalls(captureProxy, numberOfTests);

            var averageNoProxyDuration = assertBasicCalls(
                toxiproxyTestBase.getProxyUrlHttp(destinationProxy),
                numberOfTests
            );

            var acceptableProxyLatencyAdd = Duration.ofMillis(25);

            assertLessThan(
                averageNoProxyDuration.plus(acceptableProxyLatencyAdd).toMillis(),
                averageRequestDurationWithProxy.toMillis()
            );
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

    public enum FailureMode {
        LATENCY((proxy) -> proxy.toxics().latency("latency", ToxicDirection.UPSTREAM, 5000)),
        BANDWIDTH((proxy) -> proxy.toxics().bandwidth("bandwidth", ToxicDirection.DOWNSTREAM, 1)),
        TIMEOUT((proxy) -> proxy.toxics().timeout("timeout", ToxicDirection.UPSTREAM, 5000)),
        SLICER((proxy) -> {
            proxy.toxics().slicer("slicer_down", ToxicDirection.DOWNSTREAM, 1, 1000);
            proxy.toxics().slicer("slicer_up", ToxicDirection.UPSTREAM, 1, 1000);
        }),
        SLOW_CLOSE((proxy) -> proxy.toxics().slowClose("slow_close", ToxicDirection.UPSTREAM, 5000)),
        RESET_PEER((proxy) -> proxy.toxics().resetPeer("reset_peer", ToxicDirection.UPSTREAM, 5000)),
        LIMIT_DATA((proxy) -> proxy.toxics().limitData("limit_data", ToxicDirection.UPSTREAM, 10)),
        DISCONNECT(Proxy::disable);

        private final ThrowingConsumer<Proxy> failureModeApplier;

        FailureMode(ThrowingConsumer<Proxy> applier) {
            this.failureModeApplier = applier;
        }

        public void apply(Proxy proxy) {
            try {
                this.failureModeApplier.accept(proxy);
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        }
    }
}
