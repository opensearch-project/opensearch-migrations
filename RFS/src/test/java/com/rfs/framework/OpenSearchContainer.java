package com.rfs.framework;

import java.time.Duration;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import lombok.extern.slf4j.Slf4j;

/**
 * Containerized version of OpenSearch cluster
 */
@Slf4j
public class OpenSearchContainer implements AutoCloseable {
    
    private final GenericContainer<?> container;
    private final Version version;

    @SuppressWarnings("resource")
    public OpenSearchContainer(final Version version) {
        this.version = version;
        container = new GenericContainer<>(DockerImageName.parse(this.version.imageName))
                .withExposedPorts(9200, 9300)
                .withEnv("discovery.type", "single-node")
                .withEnv("plugins.security.disabled", "true")
                .waitingFor(Wait.forHttp("/").forPort(9200).forStatusCode(200).withStartupTimeout(Duration.ofMinutes(1)));
    }

    public String getUrl() {
        final var address = container.getHost();
        final var port = container.getMappedPort(9200);
        return "http://" + address + ":" + port;
    }

    @Override
    public void close() throws Exception {
        log.info("Stopping version:" + version.prettyName);
        log.debug("Instance logs:\n" + container.getLogs());
        container.stop();
    }

    public static enum Version {
        V1_3_15("opensearchproject/opensearch:1.3.16", "1.3.16"),
        V2_14_0("opensearchproject/opensearch:2.14.0", "2.14.0");

        final String imageName;
        final String prettyName;
        Version(final String imageName, final String prettyName) {
            this.imageName = imageName;
            this.prettyName = prettyName;
        }

        @Override
        public String toString() {
            return prettyName;
        }
    }
}
