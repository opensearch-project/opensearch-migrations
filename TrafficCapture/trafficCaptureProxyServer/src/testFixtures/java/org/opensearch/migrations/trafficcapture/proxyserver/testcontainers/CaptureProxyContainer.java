package org.opensearch.migrations.trafficcapture.proxyserver.testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.opensearch.migrations.testutils.PortFinder;
import org.opensearch.migrations.trafficcapture.proxyserver.CaptureProxy;

import com.github.dockerjava.api.command.InspectContainerResponse;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.containers.wait.strategy.WaitStrategyTarget;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startable;

@Slf4j
public class CaptureProxyContainer extends GenericContainer implements AutoCloseable, WaitStrategyTarget, Startable {

    private static final Duration TIMEOUT_DURATION = Duration.ofSeconds(30);
    private final Supplier<String> destinationUriSupplier;
    private final Supplier<String> kafkaUriSupplier;
    private final List<String> extraArgs;
    private final CompletableFuture<Integer> fatalExitCode = new CompletableFuture<>();
    private final AtomicBoolean stopRequested = new AtomicBoolean();
    private Integer listeningPort;
    private Thread serverThread;

    public CaptureProxyContainer(final Supplier<String> destinationUriSupplier,
                                 final Supplier<String> kafkaUriSupplier,
                                 Stream<String> extraArgs) {
        this.destinationUriSupplier = destinationUriSupplier;
        this.kafkaUriSupplier = kafkaUriSupplier;
        this.extraArgs = extraArgs.collect(Collectors.toList());
    }

    public CaptureProxyContainer(Supplier<String> destinationUriSupplier, Supplier<String> kafkaUriSupplier) {
        this(destinationUriSupplier, kafkaUriSupplier, Stream.of());
    }

    public CaptureProxyContainer(final String destinationUri, final String kafkaUri) {
        this(() -> destinationUri, () -> kafkaUri);
    }

    public CaptureProxyContainer(final Container<?> destination, final KafkaContainer kafka) {
        this(() -> getUriFromContainer(destination), () -> getUriFromContainer(kafka));
    }

    public CaptureProxyContainer(final Container<?> destination) {
        this(() -> getUriFromContainer(destination), null);
    }

    public static String getUriFromContainer(final Container<?> container) {
        return "http://" + container.getHost() + ":" + container.getFirstMappedPort();
    }

    @Override
    public void start() {
        this.listeningPort = PortFinder.findOpenPort();
        serverThread = new Thread(() -> {
            try {
                List<String> argsList = new ArrayList<>();

                if (kafkaUriSupplier != null) {
                    argsList.add("--kafkaConnection");
                    argsList.add(kafkaUriSupplier.get());
                } else {
                    argsList.add("--noCapture");
                }

                argsList.add("--destinationUri");
                argsList.add(destinationUriSupplier.get());
                argsList.add("--listenPort");
                argsList.add(String.valueOf(listeningPort));
                argsList.add("--insecureDestination");

                argsList.addAll(extraArgs);

                CaptureProxy.run(
                    argsList.toArray(new String[0]),
                    // Log4j2 belongs to the hosting JVM; the terminator flushes System.err itself.
                    () -> {},
                    exitCode -> {
                        fatalExitCode.complete(exitCode);
                        var runningThread = serverThread;
                        if (runningThread != null) {
                            runningThread.interrupt();
                        }
                    }
                );
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (!stopRequested.get() && !fatalExitCode.isDone()) {
                    throw new AssertionError("Capture proxy stopped unexpectedly", e);
                }
            } catch (Exception e) {
                throw new AssertionError("Should not have exception", e);
            }
        }, "in-process-capture-proxy");

        serverThread.start();
        new HttpWaitStrategy().forPort(listeningPort).withStartupTimeout(TIMEOUT_DURATION).waitUntilReady(this);
    }

    @Override
    public boolean isRunning() {
        return serverThread != null && serverThread.isAlive();
    }

    @Override
    public void stop() {
        stopRequested.set(true);
        var runningThread = serverThread;
        if (runningThread != null) {
            if (!fatalExitCode.isDone()) {
                runningThread.interrupt();
            }
            try {
                runningThread.join(TIMEOUT_DURATION.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (runningThread.isAlive()) {
                log.warn("Capture proxy thread did not stop within {}", TIMEOUT_DURATION);
            }
        }
        this.serverThread = null;
        this.listeningPort = null;
    }

    @Override
    public void close() {
        stop();
    }

    public int waitForFatalExit(Duration timeout) throws InterruptedException, TimeoutException {
        try {
            return fatalExitCode.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("Fatal exit observation failed", e.getCause());
        }
    }

    @Override
    public Set<Integer> getLivenessCheckPortNumbers() {
        return getExposedPorts().stream().map(this::getMappedPort).collect(Collectors.toSet());
    }

    @Override
    public String getHost() {
        return "localhost";
    }

    @Override
    public Integer getMappedPort(int originalPort) {
        if (getExposedPorts().contains(originalPort)) {
            return listeningPort;
        }
        return null;
    }

    @Override
    public List<Integer> getExposedPorts() {
        // Internal and External ports are the same
        return List.of(listeningPort);
    }

    @Override
    public InspectContainerResponse getContainerInfo() {
        return new InspectNonContainerResponse("captureProxy");
    }

    @AllArgsConstructor
    static class InspectNonContainerResponse extends InspectContainerResponse {

        private String name;

        @Override
        public String getName() {
            return name;
        }
    }
}
