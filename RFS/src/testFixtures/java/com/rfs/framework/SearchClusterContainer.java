package com.rfs.framework;

import java.io.File;
import java.time.Duration;
import java.util.Map;

import com.google.common.collect.ImmutableMap;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Containerized version of Elasticsearch cluster
 */
@Slf4j
public class SearchClusterContainer extends GenericContainer<SearchClusterContainer> {
    public static final String CLUSTER_SNAPSHOT_DIR = "/usr/share/elasticsearch/snapshots";
    public static final Version ES_V7_10_2 =
            new ElasticsearchVersion("docker.elastic.co/elasticsearch/elasticsearch-oss:7.10.2", "7.10.2");
    public static final Version ES_V7_17 =
            new ElasticsearchVersion("docker.elastic.co/elasticsearch/elasticsearch:7.17.22", "7.17.22");

    public static final Version OS_V1_3_16 =
            new OpenSearchVersion("opensearchproject/opensearch:1.3.16", "1.3.16");
    public static final Version OS_V2_14_0 =
            new OpenSearchVersion("opensearchproject/opensearch:2.14.0", "2.14.0");

    protected static Map<String, String> DEFAULT_ES_LAUNCH_ENV_VARIABLES = Map.of(
                    "discovery.type", "single-node",
                    "path.repo", CLUSTER_SNAPSHOT_DIR);

    protected static Map<String, String> DEFAULT_OS_LAUNCH_ENV_VARIABLES = new ImmutableMap.Builder<String, String>()
        .putAll(DEFAULT_ES_LAUNCH_ENV_VARIABLES)
                .put("plugins.security.disabled", "true")
                .put("OPENSEARCH_INITIAL_ADMIN_PASSWORD", "SecurityIsDisabled123$%^")
                .build();

    private final Version version;

    @SuppressWarnings("resource")
    public SearchClusterContainer(final Version version) {
        this(version, getDefaultMap(version.initializationType));
    }

    private static Map<String, String> getDefaultMap(INITIALIZATION_FLAVOR initializationType) {
        switch (initializationType) {
            case ELASTICSEARCH:
                return DEFAULT_ES_LAUNCH_ENV_VARIABLES;
            case OPENSEARCH:
                return DEFAULT_OS_LAUNCH_ENV_VARIABLES;
            default:
                throw new IllegalArgumentException("Unknown initialization flavor: " + initializationType);
        }
    }

    public SearchClusterContainer(final Version version, Map<String, String> environmentVariables) {
        super(DockerImageName.parse(version.imageName));
        this.withExposedPorts(9200, 9300)
                .withEnv(environmentVariables)
                .waitingFor(Wait.forHttp("/")
                        .forPort(9200)
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(1)));

        this.version = version;

    }

    public void copySnapshotData(final String directory) {
        log.info("Copy stuff was called");
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
        log.info("Starting ElasticsearchContainer version:" + version.prettyName);
        super.start();
    }

    public String getUrl() {
        final var address = this.getHost();
        final var port = this.getMappedPort(9200);
        return "http://" + address + ":" + port;
    }

    @Override
    public void close() {
        log.info("Stopping ElasticsearchContainer version:" + version.prettyName);
        log.debug("Instance logs:\n" + this.getLogs());
        this.stop();
    }

    public enum INITIALIZATION_FLAVOR {
        ELASTICSEARCH,
        OPENSEARCH
    }

    @EqualsAndHashCode
    @Getter
    public static class Version {
        final String imageName;
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
