package org.opensearch.migrations.trafficcapture.proxyserver;

import com.github.dockerjava.api.command.InspectContainerResponse;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.containers.wait.strategy.WaitStrategyTarget;
import org.testcontainers.lifecycle.Startable;

@Slf4j
public class ProxyCaptureContainer implements AutoCloseable, WaitStrategyTarget, Startable {

  private final Container<?> destination;
  private final Supplier<String> kafkaUriSupplier;
  private Integer listeningPort;
  private Thread serverThread;
  private static final Duration TIMEOUT_DURATION = Duration.ofSeconds(5);

  public ProxyCaptureContainer(Container<?> destination,
      Supplier<String> kafkaUriSupplier) {
    this.destination = destination;
    this.kafkaUriSupplier = kafkaUriSupplier;
  }

  public ProxyCaptureContainer(Container<?> destination,
      String kafkaUri) {
    this.destination = destination;
    this.kafkaUriSupplier = () -> kafkaUri;
  }

  public ProxyCaptureContainer(Container<?> destination,
      KafkaContainer kafka) {
    this(destination, () -> getKafkaUriFromContainer(kafka));
  }

  @Override
  public void start() {
    this.listeningPort = findOpenPort();
    serverThread = new Thread(() -> {
      try {
        String[] args = {
            "--kafkaConnection",
            kafkaUriSupplier.get(),
            "--destinationUri", "http://" + destination.getHost() + ":" + destination.getFirstMappedPort(),
            "--listenPort", String.valueOf(listeningPort),
            "--insecureDestination"
        };

        CaptureProxy.main(args);
      } catch (Exception e) {
        throw new AssertionError("Should not have exception", e);
      }
    });

    serverThread.start();
    Wait.defaultWaitStrategy()
        .withStartupTimeout(TIMEOUT_DURATION)
        .waitUntilReady(this);
  }

  public static String getKafkaUriFromContainer(KafkaContainer container) {
    return "http://" + container.getHost() + ":" + container.getFirstMappedPort();
  }


  private static int findOpenPort() {
    try (ServerSocket serverSocket = new ServerSocket(0)) {
      int port = serverSocket.getLocalPort();
      log.info("Open port found: " + port);
      return port;
    } catch (IOException e) {
      log.error("Failed to find an open port: " + e.getMessage());
      throw new RuntimeException(e);
    }
  }

  @Override
  public boolean isRunning() {
    return serverThread != null;
  }

  @Override
  public void stop() {
    if (serverThread != null) {
      serverThread.interrupt();
    }
    this.listeningPort = null;
    close();
  }

  @Override
  public void close() {
  }

  @Override
  public Set<Integer> getLivenessCheckPortNumbers() {
    return getExposedPorts()
        .stream()
        .map(this::getMappedPort)
        .collect(Collectors.toSet());
  }

  @Override
  public String getHost() {
    return "localhost"; // or the appropriate host
  }

  @Override
  public Integer getMappedPort(int originalPort) {
    if(getExposedPorts().contains(originalPort)) {
      return listeningPort;
    }
    return null;
  }

  @Override
  public List<Integer> getExposedPorts() {
    return destination.getExposedPorts();
  }

  @Override
  public InspectContainerResponse getContainerInfo() {
    return destination.getContainerInfo();
  }
}
