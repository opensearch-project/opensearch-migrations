package org.opensearch.migrations.bulkload.solr.framework;

import java.time.Duration;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

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

    public static final SolrVersion SOLR_6 = new SolrVersion("6.6.0");
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

    /** Classpath resource (shipped in this module's test fixtures) wiring Solr's S3 backup repository. */
    private static final String S3_BACKUP_SOLR_XML = "solr-s3-backup.xml";

    /**
     * Create a SolrCloud container whose backup repository writes to an S3-compatible endpoint
     * (e.g. LocalStack). Mirrors the layout Solr's {@code S3BackupRepository} expects: it copies an
     * S3-enabled {@code solr.xml}, loads the {@code s3-repository} contrib jar, and points the
     * repository at the given bucket/region/endpoint.
     *
     * @param network         shared docker network the S3 endpoint is reachable on
     * @param bucketName      S3 bucket the backup repository writes to
     * @param region          S3 region
     * @param localstackAlias network alias of the S3 endpoint (resolved as {@code http://alias:4566})
     */
    @SuppressWarnings("resource")
    public static SolrClusterContainer cloudWithS3Backup(
        SolrVersion version, Network network, String bucketName, String region, String localstackAlias
    ) {
        var container = new SolrClusterContainer(version, true);
        container.withNetwork(network)
            .withCopyFileToContainer(
                MountableFile.forClasspathResource(S3_BACKUP_SOLR_XML),
                "/var/solr/data/solr.xml")
            .withEnv("AWS_ACCESS_KEY_ID", "test")
            .withEnv("AWS_SECRET_ACCESS_KEY", "test")
            .withEnv("SOLR_OPTS",
                "-DS3_BUCKET_NAME=" + bucketName
                    + " -DS3_REGION=" + region
                    + " -DS3_ENDPOINT=http://" + localstackAlias + ":4566")
            .withEnv("SOLR_SECURITY_MANAGER_ENABLED", "false")
            .withCreateContainerCmdModifier(cmd -> cmd.withUser("root"))
            // The s3-repository jar ships under dist/ but isn't on the contrib lib path by default;
            // copy it in before launching Solr in cloud mode with the security manager disabled.
            .withCommand("bash", "-c",
                "cp /opt/solr/dist/solr-s3-repository-*.jar "
                    + "/opt/solr/contrib/s3-repository/lib/ && "
                    + "exec solr -c -f -force");
        return container;
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
