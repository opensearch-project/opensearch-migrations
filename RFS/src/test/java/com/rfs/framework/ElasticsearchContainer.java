package com.rfs.framework;

import java.time.Duration;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Containerized version of Elasticsearch cluster
 */
public class ElasticsearchContainer implements AutoCloseable {
    
    private static final Logger logger = LogManager.getLogger(ElasticsearchContainer.class);

    private final GenericContainer<?> container;
    private final Version version;

    @SuppressWarnings("resource")
    public ElasticsearchContainer(final Version version, final String localSnapshotDirectory) {
        this.version = version;
        container = new GenericContainer<>(DockerImageName.parse(this.version.imageName))
                .withExposedPorts(9200, 9300)
                .withEnv("discovery.type", "single-node")
                .withEnv("path.repo", "/snapshots")
                .withFileSystemBind(localSnapshotDirectory, "/snapshots", BindMode.READ_WRITE)
                .waitingFor(Wait.forHttp("/").forPort(9200).forStatusCode(200).withStartupTimeout(Duration.ofMinutes(1)));
    }

    public void start() {
        logger.info("Starting ElasticsearchContainer version:" + version.prettyName);
        container.start();
    }

    public String getUrl() {
        final var address = container.getHost();
        final var port = container.getMappedPort(9200);
        return "http://" + address + ":" + port;
    }

    @Override
    public void close() throws Exception {
        logger.info("Starting ElasticsearchContainer version:" + version.prettyName);
        logger.debug("Instance logs:\n" + container.getLogs());
        container.stop();
    }

    public static enum Version {
        V7_10_2("docker.elastic.co/elasticsearch/elasticsearch:7.10.2", "7.10.2");

        final String imageName;
        final String prettyName;
        Version(final String imageName, final String prettyName) {
            this.imageName = imageName;
            this.prettyName = prettyName;
        }
    }
}
