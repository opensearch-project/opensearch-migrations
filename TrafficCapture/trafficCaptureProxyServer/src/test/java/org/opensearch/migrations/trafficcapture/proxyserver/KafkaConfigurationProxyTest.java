package org.opensearch.migrations.trafficcapture.proxyserver;


import java.io.IOException;
import java.net.URI;
import java.util.function.Supplier;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.opensearch.migrations.testutils.SimpleHttpClientForTesting;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;


@Slf4j
@Testcontainers(disabledWithoutDocker = true)
public class KafkaConfigurationProxyTest {

  // see https://docs.confluent.io/platform/current/installation/versions-interoperability.html#cp-and-apache-kafka-compatibility
  private static final Supplier<KafkaContainer> getKafkaContainer = () -> new KafkaContainer(
      DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));


  private static final Supplier<GenericContainer<?>> getApacheHttpdContainer = () -> new GenericContainer<>(
      "httpd:alpine").withExposedPorts(80); // Container Port


  private static final String HTTPD_GET_EXPECTED_RESPONSE = "<html><body><h1>It works!</h1></body></html>\n";

  @Test
  @Disabled
  public void testCaptureProxyWithKafkaDownBeforeStart_proxysRequest() {
    try (
        var httpd = getApacheHttpdContainer.get();
        var proxy = new ProxyCaptureContainer(httpd, "http://kafkaNotAvailable:33323")) {

      httpd.start();

      proxy.start();

      assertBasicCall(proxy);
    }
  }

  @Test
  @Disabled
  public void testCaptureProxyWithKafkaDownAfterStart_proxysRequest() {
    try (
        var httpd = getApacheHttpdContainer.get();
        var kafka = getKafkaContainer.get();
        var proxy = new ProxyCaptureContainer(httpd, kafka)) {

      Startables.deepStart(httpd, kafka).join();

      proxy.start();

      kafka.stop();

      assertBasicCall(proxy);
    }
  }

  @Test
  public void testCaptureProxyWithKafkaAndApacheHttpd() {
    try (
        var httpd = getApacheHttpdContainer.get();
        var kafka = getKafkaContainer.get();
        var proxy = new ProxyCaptureContainer(httpd, kafka)) {

      Startables.deepStart(httpd, kafka).join();

      proxy.start();

      assertBasicCall(proxy);
    }
  }

  private void assertBasicCall(ProxyCaptureContainer proxy) {
    try (var client = new SimpleHttpClientForTesting()) {
      var proxyEndpoint = URI.create("http://localhost:" + proxy.getFirstMappedPort() + "/");
      var response = client.makeGetRequest(proxyEndpoint, Stream.empty());
      var responseBody = new String(response.payloadBytes);
      Assertions.assertEquals(HTTPD_GET_EXPECTED_RESPONSE, responseBody);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

}
