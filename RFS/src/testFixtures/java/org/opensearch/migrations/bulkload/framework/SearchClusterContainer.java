package org.opensearch.migrations.bulkload.framework;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
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

    /**
     * These settings must be injected via elasticsearch.yml for ES 5.0–5.4.
     * Verified via test: removing these lines causes container startup or snapshot repo registration to fail.
     */
    private static final List<String> ES_5_COMMON_CONFIG_LINES = List.of(
        "network.host: 0.0.0.0",
        "http.port: 9200",
        "transport.tcp.port: 9300",
        "discovery.zen.ping.unicast.hosts: []",
        "discovery.zen.minimum_master_nodes: 1",
        "node.max_local_storage_nodes: 2",
        "path.repo: \"/tmp/snapshots\"",
        "cluster.routing.allocation.disk.watermark.low: 100%",
        "cluster.routing.allocation.disk.watermark.high: 100%"
    );

    // This version of doesn't support path.repo based via env variables, passing this value via config
    private static final String OLDER_ES_CONFIG_PATH = "/usr/share/elasticsearch/config/elasticsearch.yml";
    public static final String CLUSTER_SNAPSHOT_DIR = "/tmp/snapshots";
    private static final String OLDER_ES_CONFIG =
        "network.host: 0.0.0.0\n" +
        "path.repo: \"" + CLUSTER_SNAPSHOT_DIR + "\"\n" +
        "cluster.routing.allocation.disk.watermark.low: 100%\n" +
        "cluster.routing.allocation.disk.watermark.high: 100%";

    private static String buildEs5ConfigYml(List<String> baseLines, String... extraLines) {
        List<String> allLines = new ArrayList<>(baseLines);
        Collections.addAll(allLines, extraLines);
        return String.join("\n", allLines);
    }

    private static final String ES_5_0_AND_5_1_CONFIG_YML = buildEs5ConfigYml(
        ES_5_COMMON_CONFIG_LINES
    );
    private static final String ES_5_2_AND_5_3_CONFIG_YML = buildEs5ConfigYml(
        ES_5_COMMON_CONFIG_LINES,
        "bootstrap.system_call_filter: false"
    );

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

    public static final ContainerVersion ES_V8_19 = Elasticsearch8Version.fromTag("8.19.4");
    public static final ContainerVersion ES_V8_18 = Elasticsearch8Version.fromTag("8.18.4");
    public static final ContainerVersion ES_V8_17 = Elasticsearch8Version.fromTag("8.17.5");
    public static final ContainerVersion ES_V8_16 = Elasticsearch8Version.fromTag("8.16.6");
    public static final ContainerVersion ES_V8_15 = Elasticsearch8Version.fromTag("8.15.5");
    public static final ContainerVersion ES_V8_14 = Elasticsearch8Version.fromTag("8.14.3");
    public static final ContainerVersion ES_V8_13 = Elasticsearch8Version.fromTag("8.13.4");
    public static final ContainerVersion ES_V8_12 = Elasticsearch8Version.fromTag("8.12.2");
    public static final ContainerVersion ES_V8_11 = Elasticsearch8Version.fromTag("8.11.4");
    public static final ContainerVersion ES_V8_10 = Elasticsearch8Version.fromTag("8.10.4");
    public static final ContainerVersion ES_V8_9 = Elasticsearch8Version.fromTag("8.9.2");
    public static final ContainerVersion ES_V8_8 = Elasticsearch8Version.fromTag("8.8.2");
    public static final ContainerVersion ES_V8_7 = Elasticsearch8Version.fromTag("8.7.1");
    public static final ContainerVersion ES_V8_6 = Elasticsearch8Version.fromTag("8.6.2");
    public static final ContainerVersion ES_V8_5 = Elasticsearch8Version.fromTag("8.5.3");
    public static final ContainerVersion ES_V8_4 = Elasticsearch8Version.fromTag("8.4.3");
    public static final ContainerVersion ES_V8_3 = Elasticsearch8Version.fromTag("8.3.3");
    public static final ContainerVersion ES_V8_2 = Elasticsearch8Version.fromTag("8.2.3");
    public static final ContainerVersion ES_V8_1 = Elasticsearch8Version.fromTag("8.1.3");
    public static final ContainerVersion ES_V8_0 = Elasticsearch8Version.fromTag("8.0.1");

    public static final ContainerVersion ES_V7_17 = Elasticsearch7Version.fromTag("7.17.22");
    public static final ContainerVersion ES_V7_16 = Elasticsearch7Version.fromTag("7.16.3");
    public static final ContainerVersion ES_V7_15 = Elasticsearch7Version.fromTag("7.15.2");
    public static final ContainerVersion ES_V7_14 = Elasticsearch7Version.fromTag("7.14.2");
    public static final ContainerVersion ES_V7_13 = Elasticsearch7Version.fromTag("7.13.4");
    public static final ContainerVersion ES_V7_12 = Elasticsearch7Version.fromTag("7.12.1");
    public static final ContainerVersion ES_V7_11 = Elasticsearch7Version.fromTag("7.11.2");
    public static final ContainerVersion ES_V7_10_2 = ElasticsearchOssVersion.fromTag("7.10.2");
    public static final ContainerVersion ES_V7_9 = ElasticsearchOssVersion.fromTag("7.9.3");
    public static final ContainerVersion ES_V7_8 = ElasticsearchOssVersion.fromTag("7.8.1");
    public static final ContainerVersion ES_V7_7 = ElasticsearchOssVersion.fromTag("7.7.1");
    public static final ContainerVersion ES_V7_6 = ElasticsearchOssVersion.fromTag("7.6.2");
    public static final ContainerVersion ES_V7_5 = ElasticsearchOssVersion.fromTag("7.5.2");
    public static final ContainerVersion ES_V7_4 = ElasticsearchOssVersion.fromTag("7.4.2");
    public static final ContainerVersion ES_V7_3 = ElasticsearchOssVersion.fromTag("7.3.2");
    public static final ContainerVersion ES_V7_2 = ElasticsearchOssVersion.fromTag("7.2.1");
    public static final ContainerVersion ES_V7_1 = ElasticsearchOssVersion.fromTag("7.1.1");
    public static final ContainerVersion ES_V7_0 = ElasticsearchOssVersion.fromTag("7.0.1");

    public static final ContainerVersion ES_V6_8_23 = ElasticsearchOssVersion.fromTag("6.8.23");
    public static final ContainerVersion ES_V6_7 = ElasticsearchOssVersion.fromTag("6.7.2");
    public static final ContainerVersion ES_V6_6 = ElasticsearchOssVersion.fromTag("6.6.2");
    public static final ContainerVersion ES_V6_5 = ElasticsearchOssVersion.fromTag("6.5.4");
    public static final ContainerVersion ES_V6_4 = ElasticsearchOssVersion.fromTag("6.4.3");
    public static final ContainerVersion ES_V6_3 = Elasticsearch6Version.fromTag("6.3.2", false);
    public static final ContainerVersion ES_V6_2 = Elasticsearch6Version.fromTag("6.2.4");
    public static final ContainerVersion ES_V6_1 = Elasticsearch6Version.fromTag("6.1.4");
    public static final ContainerVersion ES_V6_0 = Elasticsearch6Version.fromTag("6.0.1");

    public static final ContainerVersion ES_V5_6_16 = ElasticsearchVersion.fromTag("5.6.16");
    public static final ContainerVersion ES_V5_5 = ElasticsearchVersion.fromTag("5.5.2");
    public static final ContainerVersion ES_V5_4 = ElasticsearchVersion.fromTag("5.4.2");
    public static final ContainerVersion ES_V5_3 = Elasticsearch5Version.fromTag("5.3.2", ES_5_2_AND_5_3_CONFIG_YML);
    public static final ContainerVersion ES_V5_2 = Elasticsearch5Version.fromTag("5.2.2", ES_5_2_AND_5_3_CONFIG_YML);
    public static final ContainerVersion ES_V5_1 = Elasticsearch5Version.fromTag("5.1.2", ES_5_0_AND_5_1_CONFIG_YML);
    public static final ContainerVersion ES_V5_0 = Elasticsearch5Version.fromTag("5.0.2", ES_5_0_AND_5_1_CONFIG_YML);

    public static final ContainerVersion ES_V2_4_6 = OlderElasticsearchVersion.fromTag("2.4.6");
    public static final ContainerVersion ES_V2_3 = OlderElasticsearchVersion.fromTag("2.3.5");
    public static final ContainerVersion ES_V2_2 = OlderElasticsearchVersion.fromTag("2.2.2");
    public static final ContainerVersion ES_V2_1 = OlderElasticsearchVersion.fromTag("2.1.2");
    public static final ContainerVersion ES_V2_0 = OlderElasticsearchVersion.fromTag("2.0.2");

    public static final ContainerVersion ES_V1_7_6 = OlderElasticsearchVersion.fromTag("1.7.6");
    public static final ContainerVersion ES_V1_6 = OlderElasticsearchVersion.fromTag("1.6.2");
    public static final ContainerVersion ES_V1_5 = OlderElasticsearchVersion.fromTag("1.5.2");

    public static final ContainerVersion OS_V1_3_16 = OpenSearchVersion.fromTag("1.3.16");
    public static final ContainerVersion OS_V2_19_1 = OpenSearchVersion.fromTag("2.19.1");
    public static final ContainerVersion OS_V3_0_0 = OpenSearchVersion.fromTag("3.0.0");
    public static final ContainerVersion OS_LATEST = OS_V2_19_1;

    public enum INITIALIZATION_FLAVOR {
        BASE(Map.of("discovery.type", "single-node",
            "path.repo", CLUSTER_SNAPSHOT_DIR,
            "index.store.type", "mmapfs",
            "bootstrap.system_call_filter", "false",
            "ES_JAVA_OPTS", "-Xms2g -Xmx2g",
            "cluster.routing.allocation.disk.watermark.low", "100%",
            "cluster.routing.allocation.disk.watermark.high", "100%",
            "cluster.routing.allocation.disk.watermark.flood_stage", "100%"
        )),
        ELASTICSEARCH(
            overrideAndRemoveEnv(
                BASE.getEnvVariables(),
                Map.of("xpack.security.enabled", "false"),
                Set.of("cluster.routing.allocation.disk.watermark.flood_stage")  // drop watermark.flood_stage setting
            )),
        ELASTICSEARCH_OSS(
            overrideAndRemoveEnv(
                BASE.getEnvVariables(),
                Map.of(), // No additional keys apart from BASE
                Set.of() // No keys to remove from BASE
            )),
        ELASTICSEARCH_5(
            overrideAndRemoveEnv(
                BASE.getEnvVariables(),
                Map.of("ES_JAVA_OPTS", "-Xms1g -Xmx1g"),
                Set.of(
                    "discovery.type",
                    "ES_JAVA_OPTS",
                    "cluster.routing.allocation.disk.watermark.flood_stage"
                )
            )),
        ELASTICSEARCH_6(
            overrideAndRemoveEnv(
                BASE.getEnvVariables(),
                Map.of("ES_JAVA_OPTS", "-Xms2g -Xmx2g -Des.bootstrap.system_call_filter=false"),
                Set.of("bootstrap.system_call_filter", "ES_JAVA_OPTS") // don't set it for older ES 6x
            )),
        ELASTICSEARCH_7(
            overrideAndRemoveEnv(
                BASE.getEnvVariables(),
                Map.of("xpack.security.enabled", "false"),
                Set.of()
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
                    Map.entry("xpack.watcher.enabled", "false")
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
        public static ElasticsearchOssVersion fromTag(String tag) {
            String imageName = "docker.elastic.co/elasticsearch/elasticsearch-oss:" + tag;
            Version version = Version.fromString("ES " + tag);
            return new ElasticsearchOssVersion(imageName, version);
        }
    }

    public static class ElasticsearchVersion extends ContainerVersion {
        public ElasticsearchVersion(String imageName, Version version) {
            super(imageName, version, INITIALIZATION_FLAVOR.ELASTICSEARCH, "elasticsearch");
        }
        public static ElasticsearchVersion fromTag(String tag) {
            String imageName = "docker.elastic.co/elasticsearch/elasticsearch:" + tag;
            Version version = Version.fromString("ES " + tag);
            return new ElasticsearchVersion(imageName, version);
        }
    }

    public static class Elasticsearch8Version extends ContainerVersion {
        public Elasticsearch8Version(String imageName, Version version) {
            super(imageName, version, INITIALIZATION_FLAVOR.ELASTICSEARCH_8, "elasticsearch");
        }
        public static Elasticsearch8Version fromTag(String tag) {
            String imageName = "docker.elastic.co/elasticsearch/elasticsearch:" + tag;
            Version version = Version.fromString("ES " + tag);
            return new Elasticsearch8Version(imageName, version);
        }
    }

    public static class Elasticsearch7Version extends ContainerVersion {
        public Elasticsearch7Version(String imageName, Version version) {
            super(imageName, version, INITIALIZATION_FLAVOR.ELASTICSEARCH_7, "elasticsearch");
        }
        public static Elasticsearch7Version fromTag(String tag) {
            String imageName = "docker.elastic.co/elasticsearch/elasticsearch:" + tag;
            Version version = Version.fromString("ES " + tag);
            return new Elasticsearch7Version(imageName, version);
        }
    }

    public static class Elasticsearch6Version extends ContainerVersion {
        public Elasticsearch6Version(String imageName, Version version) {
            super(imageName, version, INITIALIZATION_FLAVOR.ELASTICSEARCH_6, "elasticsearch");
        }
        public static Elasticsearch6Version fromTag(String tag, boolean oss) {
            String prefix = oss ? "elasticsearch-oss" : "elasticsearch";
            String imageName = "docker.elastic.co/elasticsearch/" + prefix + ":" + tag;
            Version version = Version.fromString("ES " + tag);
            return new Elasticsearch6Version(imageName, version);
        }
        public static Elasticsearch6Version fromTag(String tag) {
            return fromTag(tag, true); // default to OSS
        }
    }

    public static class Elasticsearch5Version extends OlderElasticsearchVersion {
        public Elasticsearch5Version(String imageName,
                                     Version version,
                                     String filePath,
                                     String contents) {
            super(imageName, version, filePath, contents);
        }
        @Override
        public INITIALIZATION_FLAVOR getInitializationType() {
            return INITIALIZATION_FLAVOR.ELASTICSEARCH_5;
        }
        public static Elasticsearch5Version fromTag(String tag, String configContent) {
            String imageName = "elasticsearch:" + tag;
            Version version = Version.fromString("ES " + tag);
            return new Elasticsearch5Version(imageName, version, OLDER_ES_CONFIG_PATH, configContent);
        }
    }

    public static class OpenSearchVersion extends ContainerVersion {
        public OpenSearchVersion(String imageName, Version version) {
            super(imageName, version, VersionMatchers.isOS_2_19_OrGreater.test(version) ? INITIALIZATION_FLAVOR.OPENSEARCH_2_19_PLUS : INITIALIZATION_FLAVOR.OPENSEARCH, "opensearch");
        }
        public static OpenSearchVersion fromTag(String tag) {
            String imageName = "opensearchproject/opensearch:" + tag;
            Version version = Version.fromString("OS " + tag);
            return new OpenSearchVersion(imageName, version);
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
        public static OlderElasticsearchVersion fromTag(String tag) {
            String imageName = "elasticsearch:" + tag;
            Version version = Version.fromString("ES " + tag);
            return new OlderElasticsearchVersion(imageName, version, OLDER_ES_CONFIG_PATH, OLDER_ES_CONFIG);
        }
    }
}
