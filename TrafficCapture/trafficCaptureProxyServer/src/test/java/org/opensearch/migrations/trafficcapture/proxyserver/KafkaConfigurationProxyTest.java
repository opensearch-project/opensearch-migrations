package org.opensearch.migrations.trafficcapture.proxyserver;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.ThrowingConsumer;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.opensearch.migrations.testutils.SimpleHttpClientForTesting;

@Slf4j
@Execution(ExecutionMode.CONCURRENT)
public class KafkaConfigurationProxyTest extends ContainerTestBase {
  private static final String HTTPD_GET_EXPECTED_RESPONSE = "<html><body><h1>It works!</h1></body></html>\n";

  private static final int DEFAULT_NUMBER_OF_CALLS = 3;

  private static final long PROXY_EXPECTED_MAX_LATENCY_MS = Duration.ofSeconds(1).toMillis();

  @ParameterizedTest
  @EnumSource(FailureMode.class)
  @Disabled
  public void testCaptureProxyWithKafkaImpairedBeforeStart(FailureMode failureMode) {
    try (var captureProxy = new CaptureProxyContainer(destinationProxyUrl, kafkaProxyUrl)) {
      failureMode.apply(kafkaProxy);

      captureProxy.start();

      var latency = assertBasicCalls(captureProxy, DEFAULT_NUMBER_OF_CALLS);

      assertLessThan(PROXY_EXPECTED_MAX_LATENCY_MS, latency.toMillis());
    }
  }

  @ParameterizedTest
  @EnumSource(FailureMode.class)
  public void testCaptureProxyWithKafkaImpairedAfterStart(FailureMode failureMode) {
    try (var captureProxy = new CaptureProxyContainer(destinationProxyUrl, kafkaProxyUrl)) {
      captureProxy.start();

      failureMode.apply(kafkaProxy);

      var latency = assertBasicCalls(captureProxy, DEFAULT_NUMBER_OF_CALLS);

      assertLessThan(PROXY_EXPECTED_MAX_LATENCY_MS, latency.toMillis());
    }
  }

  @ParameterizedTest
  @EnumSource(FailureMode.class)
  @Execution(ExecutionMode.SAME_THREAD)
  @Tag("longTest")
  public void testCaptureProxyWithKafkaImpairedDoesNotAffectRequest_proxysRequest(
      FailureMode failureMode) {
    try (var captureProxy = new CaptureProxyContainer(destinationProxyUrl, kafkaProxyUrl)) {
      captureProxy.start();
      final int numberOfTests = 20;

      // Performance is different for first few calls so throw them away
      assertBasicCalls(captureProxy, 3);

      var averageBaselineDuration = assertBasicCalls(captureProxy, numberOfTests);

      failureMode.apply(kafkaProxy);

      // Calculate average duration of impaired calls
      var averageImpairedDuration = assertBasicCalls(captureProxy, numberOfTests);

      long acceptableDifference = Duration.ofMillis(25).toMillis();

      log.info("Baseline Duration: {}ms, Impaired Duration: {}ms",
          averageBaselineDuration.toMillis(), averageImpairedDuration.toMillis());

      assertEquals(averageBaselineDuration.toMillis(), averageImpairedDuration.toMillis(),
          acceptableDifference, "The average durations are not close enough");
    }
  }

  @Test
  @Execution(ExecutionMode.SAME_THREAD)
  @Tag("longTest")
  public void testCaptureProxyLatencyAddition() {
    try (var captureProxy = new CaptureProxyContainer(destinationProxyUrl, kafkaProxyUrl)) {
      captureProxy.start();
      final int numberOfTests = 25;

      // Performance is different for first few calls so throw them away
      assertBasicCalls(captureProxy, 3);

      var averageBaselineDuration = assertBasicCalls(captureProxy, numberOfTests);

      var averageNoProxyDuration = assertBasicCalls(destinationProxyUrl, numberOfTests);

      long acceptableDifference = Duration.ofMillis(25).toMillis();

      log.info("Baseline Duration: {}ms, NoProxy Duration: {}ms",
          averageBaselineDuration.toMillis(), averageNoProxyDuration.toMillis());

      assertEquals(averageNoProxyDuration.toMillis(), averageBaselineDuration.toMillis(),
          acceptableDifference, "The average durations are not close enough");
    }
  }

  private static void assertLessThan(long ceiling, long actual) {
    Assertions.assertTrue(actual < ceiling,
        () -> "Expected actual value to be less than " + ceiling + " but was " + actual + ".");
  }

  private Duration assertBasicCalls(CaptureProxyContainer proxy, int numberOfCalls) {
    return assertBasicCalls(CaptureProxyContainer.getUriFromContainer(proxy), numberOfCalls);
  }

  private Duration assertBasicCalls(String endpoint, int numberOfCalls) {
    return IntStream.range(0, numberOfCalls)
        .mapToObj(i -> assertBasicCall(endpoint)).reduce(Duration.ZERO, Duration::plus)
        .dividedBy(numberOfCalls);
  }


  private Duration assertBasicCall(String endpoint) {
    try (var client = new SimpleHttpClientForTesting()) {
      long startTimeNanos = System.nanoTime();
      var response = client.makeGetRequest(URI.create(endpoint), Stream.empty());
      long endTimeNanos = System.nanoTime();

      var responseBody = new String(response.payloadBytes);
      assertEquals(HTTPD_GET_EXPECTED_RESPONSE, responseBody);
      return Duration.ofNanos(endTimeNanos - startTimeNanos);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public enum FailureMode {
    LATENCY(
        (proxy) -> proxy.toxics().latency("latency", ToxicDirection.UPSTREAM, 5000)),
    BANDWIDTH(
        (proxy) -> proxy.toxics().bandwidth("bandwidth", ToxicDirection.DOWNSTREAM, 1)),
    TIMEOUT(
        (proxy) -> proxy.toxics().timeout("timeout", ToxicDirection.UPSTREAM, 5000)),
    SLICER(
        (proxy) -> {
          proxy.toxics().slicer("slicer_down", ToxicDirection.DOWNSTREAM, 1, 1000);
          proxy.toxics().slicer("slicer_up", ToxicDirection.UPSTREAM, 1, 1000);
        }),
    SLOW_CLOSE(
        (proxy) -> proxy.toxics().slowClose("slow_close", ToxicDirection.UPSTREAM, 5000)),
    RESET_PEER(
        (proxy) -> proxy.toxics().resetPeer("reset_peer", ToxicDirection.UPSTREAM, 5000)),
    LIMIT_DATA(
        (proxy) -> proxy.toxics().limitData("limit_data", ToxicDirection.UPSTREAM, 10)),
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
