package org.opensearch.migrations.trafficcapture.proxyserver;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
public class ContainerTestBase {

  private final ToxiproxyContainer toxiproxy = new ToxiproxyContainer(
      "ghcr.io/shopify/toxiproxy:latest").withAccessToHost(true);
  private final int DESTINATION_PROXY_PORT = 8666;
  private final int KAFKA_PROXY_PORT = 8667;
  public Proxy kafkaProxy = null;
  public Proxy destinationProxy = null;
  public String kafkaProxyUrl;
  public String destinationProxyUrl;

  @BeforeEach
  public void setUp() throws IOException {

    var kafkaHostPort = ReusableContainerTestSetup.kafka.getFirstMappedPort();
    var destinationHostPort = ReusableContainerTestSetup.destination.getFirstMappedPort();

    toxiproxy.start();

    org.testcontainers.Testcontainers.exposeHostPorts(kafkaHostPort);
    org.testcontainers.Testcontainers.exposeHostPorts(destinationHostPort);


    final ToxiproxyClient toxiproxyClient = new ToxiproxyClient(toxiproxy.getHost(),
        toxiproxy.getControlPort());

    kafkaProxy = toxiproxyClient.createProxy("kafka", "0.0.0.0:" + KAFKA_PROXY_PORT,
        "host.testcontainers.internal" + ":" + kafkaHostPort);
    destinationProxy = toxiproxyClient.createProxy("destination",
        "0.0.0.0:" + DESTINATION_PROXY_PORT,
        "host.testcontainers.internal" + ":" + destinationHostPort);

    kafkaProxyUrl =
        "http://" + toxiproxy.getHost() + ":" + toxiproxy.getMappedPort(KAFKA_PROXY_PORT);
    destinationProxyUrl =
        "http://" + toxiproxy.getHost() + ":" + toxiproxy.getMappedPort(DESTINATION_PROXY_PORT);

    kafkaProxy.enable();
    destinationProxy.enable();
  }

  @AfterEach
  public void tearDown() {
    if (kafkaProxy != null) {
      try {
        kafkaProxy.delete();
      } catch (IOException ignored) {}
      kafkaProxy = null;
    }
    if (destinationProxy != null) {
      try {
        destinationProxy.delete();
      } catch (IOException ignored) {}
      destinationProxy = null;
    }
  }

  public static class ReusableContainerTestSetup {

    static private final KafkaContainer kafka = new KafkaContainer(
        DockerImageName.parse("confluentinc/cp-kafka:latest"));
    static private final GenericContainer<?> destination = new GenericContainer(
        "httpd:alpine").withExposedPorts(80); // Container Port

    static {
      Startables.deepStart(kafka, destination).join();
    }
  }
}
