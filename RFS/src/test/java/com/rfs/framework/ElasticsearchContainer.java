package com.rfs.framework;

import java.io.File;
import java.time.Duration;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Containerized version of Elasticsearch cluster
 */
public class ElasticsearchContainer implements AutoCloseable {
    
    private static final Logger logger = LogManager.getLogger(ElasticsearchContainer.class);
    private static final String CLUSTER_SNAPSHOT_DIR = "/usr/share/elasticsearch/snapshots";

    private final GenericContainer<?> container;
    private final Version version;

    @SuppressWarnings("resource")
    public ElasticsearchContainer(final Version version) {
        this.version = version;
        container = new GenericContainer<>(DockerImageName.parse(this.version.imageName))
                .withExposedPorts(9200, 9300)
                .withEnv("discovery.type", "single-node")
                .withEnv("path.repo", CLUSTER_SNAPSHOT_DIR)
                .waitingFor(Wait.forHttp("/").forPort(9200).forStatusCode(200).withStartupTimeout(Duration.ofMinutes(1)));
    }

    public void copySnapshotData(final String directory) {
        logger.info("Copy stuff was called");
        try {
            // Execute command to list all files in the directory
            final var result = container.execInContainer("sh", "-c", "find " + CLUSTER_SNAPSHOT_DIR + " -type f");
            logger.debug("Process Exit Code: " + result.getExitCode());
            logger.debug("Standard Output: " + result.getStdout());
            logger.debug("Standard Error : " + result.getStderr());
            // Process each file and copy it from the container
            try (final var lines = result.getStdout().lines()) {
                lines.forEach(fullFilePath -> {
                    final var file = fullFilePath.substring(CLUSTER_SNAPSHOT_DIR.length() + 1);
                    final var sourcePath = CLUSTER_SNAPSHOT_DIR + "/" + file;
                    final var destinationPath = directory + "/" + file;
                    // Make sure the parent directory tree exists before copying
                    new File(destinationPath).getParentFile().mkdirs();
                    logger.info("Copying file " + sourcePath + " from container onto " + destinationPath);
                    container.copyFileFromContainer(sourcePath, destinationPath);
                });
            }
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
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
        logger.info("Stopping ElasticsearchContainer version:" + version.prettyName);
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
