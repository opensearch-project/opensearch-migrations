package org.testcontainers.kafka;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import com.github.dockerjava.api.command.InspectContainerResponse;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

/**
 * Local copy since org.testcontainers:kafka is not published for 2.0.x.
 * See: https://github.com/testcontainers/testcontainers-java/issues/11354
 */
public class KafkaContainer extends GenericContainer<KafkaContainer> {

    private static final DockerImageName DEFAULT_IMAGE_NAME = DockerImageName.parse("apache/kafka");
    private static final DockerImageName APACHE_KAFKA_NATIVE_IMAGE_NAME = DockerImageName.parse("apache/kafka-native");
    private static final int KAFKA_PORT = 9092;
    private static final String STARTER_SCRIPT = "/tmp/testcontainers_start.sh";

    private final Set<String> listeners = new LinkedHashSet<>();
    private final Set<Supplier<String>> advertisedListeners = new LinkedHashSet<>();

    public KafkaContainer(String imageName) {
        this(DockerImageName.parse(imageName));
    }

    public KafkaContainer(DockerImageName dockerImageName) {
        super(dockerImageName);
        dockerImageName.assertCompatibleWith(DEFAULT_IMAGE_NAME, APACHE_KAFKA_NATIVE_IMAGE_NAME);

        withExposedPorts(KAFKA_PORT);
        withEnv(KafkaHelper.envVars());
        withCommand(KafkaHelper.COMMAND);
        waitingFor(KafkaHelper.WAIT_STRATEGY);
    }

    @Override
    protected void configure() {
        KafkaHelper.resolveListeners(this, this.listeners);
    }

    @Override
    protected void containerIsStarting(InspectContainerResponse containerInfo) {
        String brokerAdvertisedListener = String.format("BROKER://%s:%s",
            containerInfo.getConfig().getHostName(), "9093");

        List<String> advertisedListeners = new ArrayList<>();
        advertisedListeners.add("PLAINTEXT://" + getBootstrapServers());
        advertisedListeners.add(brokerAdvertisedListener);
        advertisedListeners.addAll(KafkaHelper.resolveAdvertisedListeners(this.advertisedListeners));

        String command = "#!/bin/bash\n";
        command += String.format("export KAFKA_ADVERTISED_LISTENERS=%s\n", String.join(",", advertisedListeners));
        command += "/etc/kafka/docker/run \n";
        copyFileToContainer(Transferable.of(command, 0777), STARTER_SCRIPT);
    }

    public KafkaContainer withListener(String listener) {
        this.listeners.add(listener);
        this.advertisedListeners.add(() -> listener);
        return this;
    }

    public KafkaContainer withListener(String listener, Supplier<String> advertisedListener) {
        this.listeners.add(listener);
        this.advertisedListeners.add(advertisedListener);
        return this;
    }

    public String getBootstrapServers() {
        return String.format("%s:%s", getHost(), getMappedPort(KAFKA_PORT));
    }
}
