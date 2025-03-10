package org.opensearch.migrations.bulkload.framework;

import java.io.File;
import java.time.Duration;
import java.util.Map;

import org.opensearch.migrations.Version;

import com.google.common.collect.ImmutableMap;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Containerized version of Elasticsearch cluster
 */
@Slf4j
public class SearchClusterContainer extends GenericContainer<SearchClusterContainer> {
    public static final String CLUSTER_SNAPSHOT_DIR = "/tmp/snapshots";
    public static final ContainerVersion ES_V7_17 = new ElasticsearchVersion(
        "docker.elastic.co/elasticsearch/elasticsearch:7.17.22",
        Version.fromString("ES 7.17.22")
    );
    public static final ContainerVersion ES_V7_10_2 = new ElasticsearchOssVersion(
        "docker.elastic.co/elasticsearch/elasticsearch-oss:7.10.2",
        Version.fromString("ES 7.10.2")
    );
    public static final ContainerVersion ES_V6_8_23 = new ElasticsearchOssVersion(
        "docker.elastic.co/elasticsearch/elasticsearch-oss:6.8.23",
        Version.fromString("ES 6.8.23")
    );

    public static final ContainerVersion ES_V5_6_16 = new ElasticsearchVersion(
        "docker.elastic.co/elasticsearch/elasticsearch:5.6.16",
        Version.fromString("ES 5.6.16")
    );
    public static final ContainerVersion ES_V2_4_6 = new OlderElasticsearchVersion(
        "elasticsearch:2.4.6",
        Version.fromString("ES 2.4.6"),
        "/usr/share/elasticsearch/config/elasticsearch.yml",
        "network.host: 0.0.0.0\npath.repo: \"/tmp/snapshots\""
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
        BASE(Map.of("discovery.type", "single-node",
            "path.repo", CLUSTER_SNAPSHOT_DIR,
            "ES_JAVA_OPTS", "-Xms512m -Xmx512m")),
        ELASTICSEARCH(
            new ImmutableMap.Builder<String, String>().putAll(BASE.getEnvVariables())
                .put("xpack.security.enabled", "false")
                .build()),
        ELASTICSEARCH_OSS(
            new ImmutableMap.Builder<String, String>().putAll(BASE.getEnvVariables())
                .build()),
        OPENSEARCH(
            new ImmutableMap.Builder<String, String>().putAll(BASE.getEnvVariables())
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
        var builder = this.withExposedPorts(9200, 9300);

        if (version instanceof OverrideFile) {
            var overrideFile = (OverrideFile) version;
            builder = builder.withCopyToContainer(Transferable.of(overrideFile.getContents()), overrideFile.getFilePath());
        }

        builder.withEnv(version.getInitializationType().getEnvVariables())
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

    public void putSnapshotData(final String directory) {
        try {
            this.copyFileToContainer(MountableFile.forHostPath(directory), CLUSTER_SNAPSHOT_DIR);
            var user = this.containerVersion.user;
            var ls = this.execInContainer("sh", "-c", "sudo ls -ld " + CLUSTER_SNAPSHOT_DIR);
            log.atInfo().setMessage("LS result {} {}").addArgument(ls.getStdout()).addArgument(ls.getStderr()).log();
            var chown = this.execInContainer("sh", "-c", "sudo chown -R " + user + ":" + user + " " + CLUSTER_SNAPSHOT_DIR);
            log.atInfo().setMessage("CHOWN result {} {}").addArgument(chown.getStdout()).addArgument(chown.getStderr()).log();
            var chmod = this.execInContainer("sh", "-c", "sudo chmod -R 777 " + CLUSTER_SNAPSHOT_DIR);
            log.atInfo().setMessage("CHMOD result {} {}").addArgument(chmod.getStdout()).addArgument(chmod.getStderr()).log();
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }


    public void start() {
        log.info("Starting container version:" + containerVersion.version);
        super.start();

        try {            
            log.atInfo().setMessage("Container started, other activities").log();
            var user = this.containerVersion.user;
            var chown = this.execInContainer("chown", "-R", user + ":" + user, CLUSTER_SNAPSHOT_DIR);
            log.atInfo().setMessage("CHOWN result {} {}").addArgument(chown.getStdout()).addArgument(chown.getStderr()).log();

            var listUsers = this.execInContainer("cat", "/etc/passwd");
            log.atInfo().setMessage("ListUsers result {} {}").addArgument(listUsers.getStdout()).addArgument(listUsers.getStderr()).log();

            var chmod = this.execInContainer("sh", "-c", "chmod -R 777 " + CLUSTER_SNAPSHOT_DIR);
            log.atInfo().setMessage("chmod result {} {}").addArgument(chmod.getStdout()).addArgument(chmod.getStderr()).log();
            var ls = this.execInContainer("sh", "-c", "ls -ld " + CLUSTER_SNAPSHOT_DIR);
            log.atInfo().setMessage("LS result {} {}").addArgument(ls.getStdout()).addArgument(ls.getStderr()).log();

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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
    public static class ContainerVersion {
        final String imageName;
        final Version version;
        final INITIALIZATION_FLAVOR initializationType;
        final String user;

        public ContainerVersion(final String imageName, final Version version, INITIALIZATION_FLAVOR initializationType, String user) {
            this.imageName = imageName;
            this.version = version;
            this.initializationType = initializationType;
            this.user = user;
        }

        @Override
        public String toString() {
            return "Container(" + version.toString() + ")";
        }
    }

    interface OverrideFile {
        String getContents();
        String getFilePath();
    }

    public static class ElasticsearchOssVersion extends ContainerVersion {
        public ElasticsearchOssVersion(String imageName, Version version) {
            super(imageName, version, INITIALIZATION_FLAVOR.ELASTICSEARCH_OSS, "elasticsearch");
        }
    }

    public static class ElasticsearchVersion extends ContainerVersion {
        public ElasticsearchVersion(String imageName, Version version) {
            super(imageName, version, INITIALIZATION_FLAVOR.ELASTICSEARCH, "elasticsearch");
        }
    }

    public static class OpenSearchVersion extends ContainerVersion {
        public OpenSearchVersion(String imageName, Version version) {
            super(imageName, version, INITIALIZATION_FLAVOR.OPENSEARCH, "opensearch");
        }
    }

    /**
     * Older versions of elasticsearch require modifications to the configuration on disk 
     */
    @Getter
    public static class OlderElasticsearchVersion extends ElasticsearchVersion implements OverrideFile {
        private final String contents;
        private final String filePath;
        public OlderElasticsearchVersion(String imageName, Version version, String filePath, String contents) {
            super(imageName, version);
            this.contents = contents;
            this.filePath = filePath;
        }
    }
}
