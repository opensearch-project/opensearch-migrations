package org.opensearch.migrations.trafficcapture.proxyserver;


import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.opensearch.migrations.testutils.SimpleHttpClientForTesting;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.URI;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.testcontainers.utility.DockerImageName;


@Slf4j
@Testcontainers(disabledWithoutDocker = true)
public class KafkaConfigurationProxyTest {

  @Container
  // see https://docs.confluent.io/platform/current/installation/versions-interoperability.html#cp-and-apache-kafka-compatibility
  private static final KafkaContainer kafkaContainer =
      new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));


  @Container
  private static final GenericContainer<?> apacheHttpdContainer = new GenericContainer<>(
      "httpd:2.4");

  //nginx

  private static final String HTTPD_GET_EXPECTED_RESPONSE = "<html><body><h1>It works!</h1></body></html>\n";

  private static Thread serverThread;

  final private static int listeningPort = 9124;

  @AfterEach
  public void tearDown() {
    serverThread.interrupt();
    kafkaContainer.stop();
    apacheHttpdContainer.stop();
  }

  @Test
  public void testCaptureProxyWithKafkaAndApacheHttpd() throws IOException {
    kafkaContainer.start();
    apacheHttpdContainer.start();
    startProxy();
    try (var client = new SimpleHttpClientForTesting()) {
      var proxyEndpoint = URI.create("http://localhost:" + listeningPort + "/");

      var response = client.makeGetRequest(proxyEndpoint, Stream.empty());
      var responseBody = new String(response.payloadBytes);
      Assertions.assertEquals(HTTPD_GET_EXPECTED_RESPONSE, responseBody);
    }
  }


  @Test
  //@Disabled
  public void testCaptureProxyWithKafkaDownBeforeStart_proxysRequest() throws IOException {
    apacheHttpdContainer.start();
    startProxy();

    try (var client = new SimpleHttpClientForTesting()) {
      var proxyEndpoint = URI.create("http://localhost:" + listeningPort + "/");

      var response = client.makeGetRequest(proxyEndpoint, Stream.empty());
      var responseBody = new String(response.payloadBytes);
      Assertions.assertEquals(HTTPD_GET_EXPECTED_RESPONSE, responseBody);
    }
  }


  @Test
  @Disabled
  public void testCaptureProxyWithKafkaDownAfterStart_proxysRequest() throws IOException {
    kafkaContainer.start();
    startProxy();

    try (var client = new SimpleHttpClientForTesting()) {
      var proxyEndpoint = URI.create("http://localhost:" + listeningPort + "/");

      var response = client.makeGetRequest(proxyEndpoint, Stream.empty());
      var responseBody = new String(response.payloadBytes);
      Assertions.assertEquals(HTTPD_GET_EXPECTED_RESPONSE, responseBody);
    }
  }

  private void startProxy() {
    // Create a thread for starting the CaptureProxy server
    serverThread = new Thread(() -> {
      try {
        // Start the CaptureProxy server
        String[] args = {
            "--kafkaConnection",
            "http://" + kafkaContainer.getHost() + ":" + kafkaContainer.getFirstMappedPort(),
            "--destinationUri",
            "http://" + apacheHttpdContainer.getHost() + ":"
                + apacheHttpdContainer.getFirstMappedPort(),
            "--listenPort", String.valueOf(listeningPort),
            "--insecureDestination"
        };

        CaptureProxy.main(args);
      } catch (Exception e) {
        throw new AssertionError("Should not have exception", e);
      }
    });

    // Start the server thread
    serverThread.start();

    try {
      Thread.sleep(3000);
    } catch (Exception ignored) {
    }
  }
}
