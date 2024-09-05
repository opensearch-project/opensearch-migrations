package com.rfs.framework;

import java.io.File;
import java.time.Duration;
import java.util.Map;

import com.google.common.collect.ImmutableMap;

import org.opensearch.migrations.Version;

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
    public static final ContainerVersion ES_V7_10_2 = new ElasticsearchVersion(
        "docker.elastic.co/elasticsearch/elasticsearch-oss:7.10.2",
        Version.fromString("ES 7.10.2")
    );
    public static final ContainerVersion ES_V7_17 = new ElasticsearchVersion(
        "docker.elastic.co/elasticsearch/elasticsearch:7.17.22",
        Version.fromString("ES 7.17.22")
    );
    public static final ContainerVersion ES_V6_8_23 = new ElasticsearchVersion(
        "docker.elastic.co/elasticsearch/elasticsearch:6.8.23",
        Version.fromString("ES 6.8.23")
    );

    public static final ContainerVersion OS_V1_3_16 = new OpenSearchVersion(
        "opensearchproject/opensearch:1.3.16",
        Version.fromString("OS 1.3.16")
    );
    public static final ContainerVersion OS_V2_14_0 = new OpenSearchVersion(
        "opensearchproject/opensearch:2.14.0",
        Version.fromString("OS 2.14.0")
    );

    private enum INITIALIZATION_FLAVOR {
        ELASTICSEARCH(Map.of(
            "discovery.type", "single-node",
            "path.repo", CLUSTER_SNAPSHOT_DIR,
            "ES_JAVA_OPTS", "-Xms512m -Xmx512m")),
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

    @Getter
    private final ContainerVersion containerVersion;

    @SuppressWarnings("resource")
    public SearchClusterContainer(final ContainerVersion version) {
        super(DockerImageName.parse(version.imageName));
        this.withExposedPorts(9200, 9300)
            .withEnv(version.getInitializationType().getEnvVariables())
            .waitingFor(Wait.forHttp("/").forPort(9200).forStatusCode(200).withStartupTimeout(Duration.ofMinutes(1)));

        this.containerVersion = version;
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
        log.info("Starting container version:" + containerVersion.version);
        super.start();
    }

    public String getUrl() {
        final var address = this.getHost();
        final var port = this.getMappedPort(9200);
        return "http://" + address + ":" + port;
    }

    @Override
    public void close() {
        log.info("Stopping container version:" + containerVersion.version);
        log.debug("Instance logs:\n" + this.getLogs());
        this.stop();
    }

    @EqualsAndHashCode
    @Getter
    @ToString(onlyExplicitlyIncluded = true, includeFieldNames = false)
    public static class ContainerVersion {
        final String imageName;
        @ToString.Include
        final Version version;
        final INITIALIZATION_FLAVOR initializationType;

        public ContainerVersion(final String imageName, final Version version, INITIALIZATION_FLAVOR initializationType) {
            this.imageName = imageName;
            this.version = version; 
            this.initializationType = initializationType;
        }

    }

    public static class ElasticsearchVersion extends ContainerVersion {
        public ElasticsearchVersion(String imageName, Version version) {
            super(imageName, version, INITIALIZATION_FLAVOR.ELASTICSEARCH);
        }
    }

    public static class OpenSearchVersion extends ContainerVersion {
        public OpenSearchVersion(String imageName, Version version) {
            super(imageName, version, INITIALIZATION_FLAVOR.OPENSEARCH);
        }
    }
}
