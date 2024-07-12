package com.rfs.framework;

import java.io.File;
import java.time.Duration;
import java.util.Map;

import com.google.common.collect.ImmutableMap;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Containerized version of Elasticsearch cluster
 */
@Slf4j
public class SearchClusterContainer extends GenericContainer<SearchClusterContainer> {
    public static final String CLUSTER_SNAPSHOT_DIR = "/tmp/snapshots";
    public static final Version ES_V7_10_2 = new ElasticsearchVersion(
        "docker.elastic.co/elasticsearch/elasticsearch-oss:7.10.2",
        "ES 7.10.2"
    );
    public static final Version ES_V7_17 = new ElasticsearchVersion(
        "docker.elastic.co/elasticsearch/elasticsearch:7.17.22",
        "ES 7.17.22"
    );
    public static final Version ES_V6_8_23 = new ElasticsearchVersion(
        "docker.elastic.co/elasticsearch/elasticsearch:6.8.23",
        "ES 6.8.23"
    );

    public static final Version OS_V1_3_16 = new OpenSearchVersion("opensearchproject/opensearch:1.3.16", "OS 1.3.16");
    public static final Version OS_V2_14_0 = new OpenSearchVersion("opensearchproject/opensearch:2.14.0", "OS 2.14.0");

    private enum INITIALIZATION_FLAVOR {
        ELASTICSEARCH(Map.of("discovery.type", "single-node", "path.repo", CLUSTER_SNAPSHOT_DIR)),
        OPENSEARCH(
            new ImmutableMap.Builder<String, String>().putAll(ELASTICSEARCH.getEnvVariables())
                .put("plugins.security.disabled", "true")
                .put("OPENSEARCH_INITIAL_ADMIN_PASSWORD", "SecurityIsDisabled123$%^")
                .build()
        );

        @Getter
        public final Map<String, String> envVariables;

        INITIALIZATION_FLAVOR(Map<String, String> envVariables) {
            this.envVariables = envVariables;
        }
    }

    private final Version version;

    @SuppressWarnings("resource")
    public SearchClusterContainer(final Version version) {
        super(DockerImageName.parse(version.imageName));
        this.withExposedPorts(9200, 9300)
            .withEnv(version.getInitializationType().getEnvVariables())
            .waitingFor(Wait.forHttp("/").forPort(9200).forStatusCode(200).withStartupTimeout(Duration.ofMinutes(1)));

        this.version = version;
    }

    public void copySnapshotData(final String directory) {
        try {
            // Execute command to list all files in the directory
            final var result = this.execInContainer("sh", "-c", "find " + CLUSTER_SNAPSHOT_DIR + " -type f");
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
                    this.copyFileFromContainer(sourcePath, destinationPath);
                });
            }
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void start() {
        log.info("Starting container version:" + version.prettyName);
        super.start();
    }

    public String getUrl() {
        final var address = this.getHost();
        final var port = this.getMappedPort(9200);
        return "http://" + address + ":" + port;
    }

    @Override
    public void close() {
        log.info("Stopping container version:" + version.prettyName);
        log.debug("Instance logs:\n" + this.getLogs());
        this.stop();
    }

    @EqualsAndHashCode
    @Getter
    @ToString(onlyExplicitlyIncluded = true, includeFieldNames = false)
    public static class Version {
        final String imageName;
        @ToString.Include
        final String prettyName;
        final INITIALIZATION_FLAVOR initializationType;

        public Version(final String imageName, final String prettyName, INITIALIZATION_FLAVOR initializationType) {
            this.imageName = imageName;
            this.prettyName = prettyName;
            this.initializationType = initializationType;
        }
    }

    public static class ElasticsearchVersion extends Version {
        public ElasticsearchVersion(String imageName, String prettyName) {
            super(imageName, prettyName, INITIALIZATION_FLAVOR.ELASTICSEARCH);
        }
    }

    public static class OpenSearchVersion extends Version {
        public OpenSearchVersion(String imageName, String prettyName) {
            super(imageName, prettyName, INITIALIZATION_FLAVOR.OPENSEARCH);
        }
    }
}
