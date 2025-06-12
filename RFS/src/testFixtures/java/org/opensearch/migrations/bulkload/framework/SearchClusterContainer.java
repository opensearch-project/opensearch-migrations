package org.opensearch.migrations.bulkload.framework;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.VersionMatchers;

import com.google.common.collect.ImmutableMap;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.testcontainers.containers.ExecConfig;
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

    private static Map<String, String> overrideAndRemoveEnv(
            Map<String, String> base,
            Map<String, String> overrides,
            Set<String> keysToRemove
    ) {
        Map<String, String> merged = new HashMap<>(base);
        keysToRemove.forEach(merged::remove);
        merged.putAll(overrides);
        return Collections.unmodifiableMap(merged);
    }

    public static final ContainerVersion ES_V7_17 = new ElasticsearchVersion(
        "docker.elastic.co/elasticsearch/elasticsearch:7.17.22",
        Version.fromString("ES 7.17.22")
    );
    public static final ContainerVersion ES_V8_17 = new Elasticsearch8Version(
        "docker.elastic.co/elasticsearch/elasticsearch:8.17.5",
        Version.fromString("ES 8.17.5")
    );
    public static final ContainerVersion ES_V7_9 = new ElasticsearchOssVersion(
            "docker.elastic.co/elasticsearch/elasticsearch-oss:7.9.3",
            Version.fromString("ES 7.9.3")
    );
    public static final ContainerVersion ES_V7_8 = new ElasticsearchOssVersion(
            "docker.elastic.co/elasticsearch/elasticsearch-oss:7.8.1",
            Version.fromString("ES 7.8.1")
    );
    public static final ContainerVersion ES_V7_7 = new ElasticsearchOssVersion(
            "docker.elastic.co/elasticsearch/elasticsearch-oss:7.7.1",
            Version.fromString("ES 7.7.1")
    );
    public static final ContainerVersion ES_V7_4 = new ElasticsearchOssVersion(
            "docker.elastic.co/elasticsearch/elasticsearch-oss:7.4.2",
            Version.fromString("ES 7.4.2")
    );
    public static final ContainerVersion ES_V7_1 = new ElasticsearchOssVersion(
            "docker.elastic.co/elasticsearch/elasticsearch-oss:7.1.1",
            Version.fromString("ES 7.1.1")
    );
    public static final ContainerVersion ES_V7_10_2 = new ElasticsearchOssVersion(
        "docker.elastic.co/elasticsearch/elasticsearch-oss:7.10.2",
        Version.fromString("ES 7.10.2")
    );
    public static final ContainerVersion ES_V6_8_23 = new ElasticsearchOssVersion(
        "docker.elastic.co/elasticsearch/elasticsearch-oss:6.8.23",
        Version.fromString("ES 6.8.23")
    );
    public static final ContainerVersion ES_V6_7 = new ElasticsearchOssVersion(
            "docker.elastic.co/elasticsearch/elasticsearch-oss:6.7.2",
            Version.fromString("ES 6.7.2")
    );
    public static final ContainerVersion ES_V6_6 = new ElasticsearchOssVersion(
            "docker.elastic.co/elasticsearch/elasticsearch-oss:6.6.2",
            Version.fromString("ES 6.6.2")
    );
    public static final ContainerVersion ES_V6_5 = new ElasticsearchOssVersion(
            "docker.elastic.co/elasticsearch/elasticsearch-oss:6.5.4",
            Version.fromString("ES 6.5.4")
    );
    public static final ContainerVersion ES_V6_4 = new ElasticsearchOssVersion(
            "docker.elastic.co/elasticsearch/elasticsearch-oss:6.4.3",
            Version.fromString("ES 6.4.3")
    );
    public static final ContainerVersion ES_V6_3 = new Elasticsearch6Version(
            "docker.elastic.co/elasticsearch/elasticsearch:6.3.2",
            Version.fromString("ES 6.3.2")
    );
    public static final ContainerVersion ES_V6_2 = new Elasticsearch6Version(
            "docker.elastic.co/elasticsearch/elasticsearch-oss:6.2.4",
            Version.fromString("ES 6.2.4")
    );
    public static final ContainerVersion ES_V6_1 = new Elasticsearch6Version(
            "docker.elastic.co/elasticsearch/elasticsearch-oss:6.1.4",
            Version.fromString("ES 6.1.4")
    );
    public static final ContainerVersion ES_V6_0 = new Elasticsearch6Version(
            "docker.elastic.co/elasticsearch/elasticsearch-oss:6.0.1",
            Version.fromString("ES 6.0.1")
    );

    public static final ContainerVersion ES_V5_6_16 = new ElasticsearchVersion(
        "docker.elastic.co/elasticsearch/elasticsearch:5.6.16",
        Version.fromString("ES 5.6.16")
    );

    public static final ContainerVersion ES_V2_4_6 = new OlderElasticsearchVersion(
        "elasticsearch:2.4.6",
        Version.fromString("ES 2.4.6"),
        // This version of doesn't support path.repo based via env variables, passing this value via config 
        "/usr/share/elasticsearch/config/elasticsearch.yml",
        "network.host: 0.0.0.0\npath.repo: \"/tmp/snapshots\""
    );

    public static final ContainerVersion OS_V1_3_16 = new OpenSearchVersion(
        "opensearchproject/opensearch:1.3.16",
        Version.fromString("OS 1.3.16")
    );
    public static final ContainerVersion OS_V2_19_1 = new OpenSearchVersion(
        "opensearchproject/opensearch:2.19.1",
        Version.fromString("OS 2.19.1")
    );
    public static final ContainerVersion OS_V3_0_0 = new OpenSearchVersion(
        "opensearchproject/opensearch:3.0.0",
        Version.fromString("OS 3.0.0")
    );
    
    public static final ContainerVersion OS_LATEST = OS_V2_19_1;

    private enum INITIALIZATION_FLAVOR {
        BASE(Map.of("discovery.type", "single-node",
            "path.repo", CLUSTER_SNAPSHOT_DIR,
            "index.store.type", "mmapfs",
            "bootstrap.system_call_filter", "false",
            "ES_JAVA_OPTS", "-Xms2g -Xmx2g"
        )),
        ELASTICSEARCH(
            overrideAndRemoveEnv(
                BASE.getEnvVariables(),
                Map.of(
                    "xpack.security.enabled", "false"
                ),
                Set.of()  // No keys to remove from BASE
            )),
        ELASTICSEARCH_OSS(
            overrideAndRemoveEnv(
                BASE.getEnvVariables(),
                Map.of(), // No additional keys apart from BASE
                Set.of() // No keys to remove from BASE
            )),
        ELASTICSEARCH_6(
            overrideAndRemoveEnv(
                BASE.getEnvVariables(),
                Map.of("ES_JAVA_OPTS", "-Xms2g -Xmx2g -Des.bootstrap.system_call_filter=false"),
                Set.of("bootstrap.system_call_filter", "ES_JAVA_OPTS") // don't set it for older ES 6x
            )),
        ELASTICSEARCH_8(
            overrideAndRemoveEnv(
                BASE.getEnvVariables(),
                Map.ofEntries(
                    Map.entry("xpack.security.enabled", "false"),
                    Map.entry("xpack.security.enrollment.enabled", "false"),
                    Map.entry("xpack.security.http.ssl.enabled", "false"),
                    Map.entry("xpack.security.transport.ssl.enabled", "false"),
                    Map.entry("cluster.name", "docker-test-cluster"),
                    Map.entry("node.name", "test-node"),
                    Map.entry("xpack.ml.enabled", "false"),
                    Map.entry("xpack.watcher.enabled", "false"),
                    Map.entry("cluster.routing.allocation.disk.watermark.low", "95%"),
                    Map.entry("cluster.routing.allocation.disk.watermark.high", "98%"),
                    Map.entry("cluster.routing.allocation.disk.watermark.flood_stage", "99%")
                ),
                Set.of("bootstrap.system_call_filter")  // don't set it for ES 8x
            )),
        OPENSEARCH(
            overrideAndRemoveEnv(
                BASE.getEnvVariables(),
                Map.of(
                        "plugins.security.disabled", "true",
                        "OPENSEARCH_INITIAL_ADMIN_PASSWORD", "SecurityIsDisabled123$%^"
                ),
                Set.of()  // No keys to remove from BASE
            )),
        OPENSEARCH_2_19_PLUS(
            overrideAndRemoveEnv(
                BASE.getEnvVariables(),
                Map.of(
                    "plugins.security.disabled", "true",
                    "OPENSEARCH_INITIAL_ADMIN_PASSWORD", "SecurityIsDisabled123$%^",
                    "search.insights.top_queries.exporter.type", "debug"
                ),
                Set.of()  // No keys to remove from BASE
            ));

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

    public SearchClusterContainer(final ContainerVersion version, Map<String, String> supplementaryEnvVariables) {
        super(DockerImageName.parse(version.imageName));
        var builder = this.withExposedPorts(9200, 9300);

        var combinedEnvVariables = new ImmutableMap.Builder<String, String>().putAll(
                                        version.getInitializationType().getEnvVariables()).putAll(
                                        supplementaryEnvVariables
                                    ).build();
        builder.withEnv(combinedEnvVariables)
                .waitingFor(Wait.forHttp("/").forPort(9200).forStatusCode(200).withStartupTimeout(Duration.ofMinutes(1)));
        this.containerVersion = version;
    }


    public void copySnapshotData(final String directory) {
        try {
            // Execute command to list all files in the directory
            // `find` is not available on some versions of the containers, in which case it falls back to a ls/grep loop.
            final var result = this.execInContainer("sh", "-c", "find " + CLUSTER_SNAPSHOT_DIR + " -type f" + " || " +
                    "for dir in $(ls -1 -R " + CLUSTER_SNAPSHOT_DIR + " | grep ':' | sed 's/://g'); do for file in $(ls -1 $dir); do if [ -f \"$dir/$file\" ]; then echo \"$dir/$file\"; fi; done; done");
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
            executeAndLog(ExecConfig.builder()
                .command(new String[] {"sh", "-c", "chown -R " + user + ":" + user + " " + CLUSTER_SNAPSHOT_DIR})
                .user("root")
                .build());
            executeAndLog(ExecConfig.builder()
                .command(new String[] {"sh", "-c", "chmod -R 777 " + CLUSTER_SNAPSHOT_DIR})
                .user("root")
                .build());
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void executeAndLog(ExecConfig command) throws UnsupportedOperationException, IOException, InterruptedException {
            var result = this.execInContainer(command);
            log.atInfo()
                .setMessage("Command result: {} as <{}>\nStdOut:\n{}\nStdErr:\n{}")
                .addArgument(command.getCommand())
                .addArgument(command.getUser())
                .addArgument(result.getStdout())
                .addArgument(result.getStderr())
                .log();
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

    public static class Elasticsearch8Version extends ContainerVersion {
        public Elasticsearch8Version(String imageName, Version version) {
            super(imageName, version, INITIALIZATION_FLAVOR.ELASTICSEARCH_8, "elasticsearch");
        }
    }

    public static class Elasticsearch6Version extends ContainerVersion {
        public Elasticsearch6Version(String imageName, Version version) {
            super(imageName, version, INITIALIZATION_FLAVOR.ELASTICSEARCH_6, "elasticsearch");
        }
    }

    public static class OpenSearchVersion extends ContainerVersion {
        public OpenSearchVersion(String imageName, Version version) {
            super(imageName, version, VersionMatchers.isOS_2_19_OrGreater.test(version) ? INITIALIZATION_FLAVOR.OPENSEARCH_2_19_PLUS : INITIALIZATION_FLAVOR.OPENSEARCH, "opensearch");
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
