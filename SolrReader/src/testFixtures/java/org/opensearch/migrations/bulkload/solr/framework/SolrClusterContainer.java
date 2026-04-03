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
 */
@Slf4j
public class SolrClusterContainer extends GenericContainer<SolrClusterContainer> {

    public static final SolrVersion SOLR_8 = new SolrVersion("8.11.4");
    public static final SolrVersion SOLR_9 = new SolrVersion("9.8.1");

    @Getter
    private final SolrVersion solrVersion;

    @SuppressWarnings("resource")
    public SolrClusterContainer(SolrVersion version) {
        super(DockerImageName.parse("solr:" + version.tag()));
        this.solrVersion = version;
        this.withExposedPorts(8983)
            .withCommand("solr-precreate", "dummy") // start Solr in standalone mode
            .waitingFor(Wait.forHttp("/solr/admin/info/system")
                .forPort(8983)
                .forStatusCode(200)
                .withStartupTimeout(Duration.ofMinutes(2)));
    }

    public String getSolrUrl() {
        return "http://" + getHost() + ":" + getMappedPort(8983);
    }

    @Override
    public void start() {
        log.info("Starting Solr container: {}", solrVersion);
        super.start();
    }

    @Override
    public void close() {
        log.info("Stopping Solr container: {}", solrVersion);
        log.debug("Solr logs:\n{}", getLogs());
        super.close();
    }

    public record SolrVersion(String tag) {
        @Override
        public String toString() {
            return "Solr(" + tag + ")";
        }
    }
}
