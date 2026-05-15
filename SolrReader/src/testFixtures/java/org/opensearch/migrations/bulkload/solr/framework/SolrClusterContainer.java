package org.opensearch.migrations.bulkload.solr.framework;

import java.time.Duration;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Testcontainers wrapper for Solr, following the project's container pattern.
 * Uses GenericContainer directly to avoid testcontainers-solr module compatibility issues.
 *
 * <p>Supports two modes:
 * <ul>
 *   <li>Standalone (default): {@code new SolrClusterContainer(version)} — single-node, core-based</li>
 *   <li>SolrCloud: {@code SolrClusterContainer.cloud(version)} — embedded ZooKeeper, collection-based with sharding</li>
 * </ul>
 */
@Slf4j
public class SolrClusterContainer extends GenericContainer<SolrClusterContainer> {

    public static final SolrVersion SOLR_6 = new SolrVersion("6.6.6");
    public static final SolrVersion SOLR_7 = new SolrVersion("7.7.3");
    public static final SolrVersion SOLR_8 = new SolrVersion("8.11.4");
    public static final SolrVersion SOLR_9 = new SolrVersion("9.8.1");

    @Getter
    private final SolrVersion solrVersion;
    @Getter
    private final boolean cloudMode;

    /** Create a standalone Solr container. */
    @SuppressWarnings("resource")
    public SolrClusterContainer(SolrVersion version) {
        this(version, false);
    }

    @SuppressWarnings("resource")
    private SolrClusterContainer(SolrVersion version, boolean cloudMode) {
        super(DockerImageName.parse("solr:" + version.tag()));
        this.solrVersion = version;
        this.cloudMode = cloudMode;
        this.withExposedPorts(8983);
        if (cloudMode) {
            this.withCommand("solr", "-c", "-f")
                .waitingFor(Wait.forHttp("/solr/admin/collections?action=LIST&wt=json")
                    .forPort(8983)
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(3)));
        } else {
            this.withCommand("solr-precreate", "dummy")
                .waitingFor(Wait.forHttp("/solr/admin/cores?action=STATUS&indexInfo=false&wt=json")
                    .forPort(8983)
                    .forStatusCode(200)
                    .forResponsePredicate(body -> body.contains("\"dummy\""))
                    .withStartupTimeout(Duration.ofMinutes(2)));
        }
    }

    /** Create a SolrCloud container with embedded ZooKeeper. */
    public static SolrClusterContainer cloud(SolrVersion version) {
        return new SolrClusterContainer(version, true);
    }

    public String getSolrUrl() {
        return "http://" + getHost() + ":" + getMappedPort(8983);
    }

    @Override
    public void start() {
        log.atInfo().setMessage("Starting Solr container: {} (cloud={})").addArgument(solrVersion).addArgument(cloudMode).log();
        super.start();
    }

    @Override
    public void close() {
        log.atInfo().setMessage("Stopping Solr container: {}").addArgument(solrVersion).log();
        log.atDebug().setMessage("Solr logs:\n{}").addArgument(this::getLogs).log();
        super.close();
    }

    public record SolrVersion(String tag) {
        public int major() {
            return Integer.parseInt(tag.split("\\.")[0]);
        }

        @Override
        public String toString() {
            return "Solr(" + tag + ")";
        }
    }
}
