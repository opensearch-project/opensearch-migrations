package com.rfs.framework;

import java.io.File;
import java.time.Duration;

import lombok.extern.slf4j.Slf4j;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Containerized version of Elasticsearch cluster
 */
@Slf4j
public class ElasticsearchContainer implements AutoCloseable {
    public static final String CLUSTER_SNAPSHOT_DIR = "/usr/share/elasticsearch/snapshots";
    public static final Version V7_10_2 =
            new Version("docker.elastic.co/elasticsearch/elasticsearch:7.10.2", "7.10.2");

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
        log.info("Copy stuff was called");
        try {
            // Execute command to list all files in the directory
            final var result = container.execInContainer("sh", "-c", "find " + CLUSTER_SNAPSHOT_DIR + " -type f");
            log.debug("Process Exit Code: " + result.getExitCode());
            log.debug("Standard Output: " + result.getStdout());
            log.debug("Standard Error : " + result.getStderr());
            // Process each file and copy it from the container
            try (final var lines = result.getStdout().lines()) {
                lines.forEach(fullFilePath -> {
                    final var file = fullFilePath.substring(CLUSTER_SNAPSHOT_DIR.length() + 1);
                    final var sourcePath = CLUSTER_SNAPSHOT_DIR + "/" + file;
                    final var destinationPath = directory + "/" + file;
                    // Make sure the parent directory tree exists before copying
                    new File(destinationPath).getParentFile().mkdirs();
                    log.info("Copying file " + sourcePath + " from container onto " + destinationPath);
                    container.copyFileFromContainer(sourcePath, destinationPath);
                });
            }
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void start() {
        log.info("Starting ElasticsearchContainer version:" + version.prettyName);
        container.start();
    }

    public String getUrl() {
        final var address = container.getHost();
        final var port = container.getMappedPort(9200);
        return "http://" + address + ":" + port;
    }

    @Override
    public void close() throws Exception {
        log.info("Stopping ElasticsearchContainer version:" + version.prettyName);
        log.debug("Instance logs:\n" + container.getLogs());
        container.stop();
    }

    public static class Version {
        final String imageName;
        final String prettyName;
        public Version(final String imageName, final String prettyName) {
            this.imageName = imageName;
            this.prettyName = prettyName;
        }
    }
}
