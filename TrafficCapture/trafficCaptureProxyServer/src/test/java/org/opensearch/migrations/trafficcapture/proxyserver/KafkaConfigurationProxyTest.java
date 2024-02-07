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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.ThrowingConsumer;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.opensearch.migrations.testutils.SimpleHttpClientForTesting;
import org.testcontainers.junit.jupiter.Testcontainers;

@Slf4j
@Testcontainers(disabledWithoutDocker = true)
public class KafkaConfigurationProxyTest extends ContainerTestBase {

  private static final String HTTPD_GET_EXPECTED_RESPONSE = "<html><body><h1>It works!</h1></body></html>\n";

  @ParameterizedTest
  @EnumSource(FailureMode.class)
  public void testCaptureProxyWithKafkaImpairedBeforeStart(FailureMode failureMode) {
    try (var captureProxy = new CaptureProxyContainer(destinationProxyUrl, kafkaProxyUrl)) {
      failureMode.apply(kafkaProxy);

      captureProxy.start();

      var latency = assertBasicCall(captureProxy);

      Assertions.assertTrue(latency.minus(Duration.ofMillis(100)).isNegative(),
          "Latency must be less than 100ms");
    }
  }

  @ParameterizedTest
  @EnumSource(FailureMode.class)
  public void testCaptureProxyWithKafkaImpairedAfterStart(FailureMode failureMode) {
    try (var captureProxy = new CaptureProxyContainer(destinationProxyUrl, kafkaProxyUrl)) {
      captureProxy.start();

      failureMode.apply(kafkaProxy);

      var latency = assertBasicCall(captureProxy);

      Assertions.assertTrue(latency.minus(Duration.ofMillis(100)).isNegative(),
          "Latency must be less than 100ms");
    }
  }

  @ParameterizedTest
  @EnumSource(FailureMode.class)
  @Execution(ExecutionMode.SAME_THREAD)
  public void testCaptureProxyWithKafkaImpairedDoesNotAffectRequest_proxysRequest(
      FailureMode failureMode) {
    try (var captureProxy = new CaptureProxyContainer(destinationProxyUrl, kafkaProxyUrl)) {
      captureProxy.start();
      final int numberOfTests = 20;

      // Performance is different for first few calls so throw them away
      IntStream.range(0, 3).forEach(i -> assertBasicCall(captureProxy));

      // Calculate average duration of baseline calls
      Duration averageBaselineDuration = IntStream.range(0, numberOfTests)
          .mapToObj(i -> assertBasicCall(captureProxy)).reduce(Duration.ZERO, Duration::plus)
          .dividedBy(numberOfTests);

      failureMode.apply(kafkaProxy);

      // Calculate average duration of impaired calls
      Duration averageImpairedDuration = IntStream.range(0, numberOfTests)
          .mapToObj(i -> assertBasicCall(captureProxy)).reduce(Duration.ZERO, Duration::plus)
          .dividedBy(numberOfTests);

      long acceptableDifference = Duration.ofMillis(25).toMillis();

      log.info("Baseline Duration: {}ms, Impaired Duration: {}ms",
          averageBaselineDuration.toMillis(), averageImpairedDuration.toMillis());

      assertEquals(averageBaselineDuration.toMillis(), averageImpairedDuration.toMillis(),
          acceptableDifference, "The average durations are not close enough");
    }
  }

  @Test
  @Execution(ExecutionMode.SAME_THREAD)
  public void testCaptureProxyLatencyAddition() {
    try (var captureProxy = new CaptureProxyContainer(destinationProxyUrl, kafkaProxyUrl)) {
      captureProxy.start();
      final int numberOfTests = 25;

      // Performance is different for first few calls so throw them away
      IntStream.range(0, 3).forEach(i -> assertBasicCall(captureProxy));

      // Calculate average duration of baseline calls
      Duration averageBaselineDuration = IntStream.range(0, numberOfTests)
          .mapToObj(i -> assertBasicCall(captureProxy)).reduce(Duration.ZERO, Duration::plus)
          .dividedBy(numberOfTests);

      // Calculate average duration of impaired calls
      Duration averageImpairedDuration = IntStream.range(0, numberOfTests)
          .mapToObj(i -> assertBasicCall(destinationProxyUrl)).reduce(Duration.ZERO, Duration::plus)
          .dividedBy(numberOfTests);

      long acceptableDifference = Duration.ofMillis(25).toMillis();

      log.info("Baseline Duration: {}ms, Impaired Duration: {}ms",
          averageBaselineDuration.toMillis(), averageImpairedDuration.toMillis());

      assertEquals(averageImpairedDuration.toMillis(), averageBaselineDuration.toMillis(),
          acceptableDifference, "The average durations are not close enough");
    }
  }

  private Duration assertBasicCall(CaptureProxyContainer proxy) {
    return assertBasicCall(CaptureProxyContainer.getUriFromContainer(proxy));
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
        (proxy) -> proxy.toxics().bandwidth("bandwidth", ToxicDirection.UPSTREAM, 5000)),
    TIMEOUT(
        (proxy) -> proxy.toxics().timeout("timeout", ToxicDirection.UPSTREAM, 5000)),
    SLICER(
        (proxy) -> proxy.toxics().slicer("slicer", ToxicDirection.DOWNSTREAM, 10, 10)),
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
