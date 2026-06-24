package org.opensearch.migrations;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.bulkload.solr.framework.SolrClusterContainer;
import org.opensearch.migrations.metadata.tracing.MetadataMigrationTestContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Issue #3147 — end-to-end proof that MetadataMigration's S3 branch
 * ({@link org.opensearch.migrations.cli.ClusterReaderExtractor}) handles a bare SolrCloud 7 backup
 * uploaded to S3 <em>verbatim</em>, recovering the index name from {@code backup.properties} and
 * producing the correct OpenSearch mappings — with no reshape step.
 *
 * <p>The existing {@link SolrS3MetadataMigrationTest} only covers Solr 8's <em>wrapped</em> two-level
 * layout; this closes the metadata-path gap for the bare layout. Solr 7 has no S3 backup repository,
 * so this mirrors the real flow: BACKUP to local disk, then upload the tree to S3 unchanged.
 */
@Slf4j
@Tag("isolatedTest")
@Testcontainers
class SolrBareS3MetadataMigrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BUCKET_NAME = "solr-bare-meta-s3";
    private static final String REGION = "us-east-1";
    private static final String LOCALSTACK_ALIAS = "localstack";
    private static final String COLLECTION = "cloud_test";
    private static final String SNAPSHOT_NAME = "cloud_backup";

    static {
        if (System.getProperty("aws.accessKeyId") == null) {
            System.setProperty("aws.accessKeyId", "test");
        }
        if (System.getProperty("aws.secretAccessKey") == null) {
            System.setProperty("aws.secretAccessKey", "test");
        }
        if (System.getProperty("aws.region") == null) {
            System.setProperty("aws.region", REGION);
        }
    }

    static final Network NETWORK = Network.newNetwork();

    @Container
    static final LocalStackContainer LOCAL_STACK = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:4.3.0"))
        .withServices(LocalStackContainer.Service.S3)
        .withNetwork(NETWORK)
        .withNetworkAliases(LOCALSTACK_ALIAS);

    @Container
    static final SolrClusterContainer SOLR_CLOUD = SolrClusterContainer.cloud(SolrClusterContainer.SOLR_7);

    @BeforeAll
    static void createBucket() throws Exception {
        LOCAL_STACK.execInContainer("awslocal", "s3", "mb", "s3://" + BUCKET_NAME);
    }

    @TempDir
    Path tempDir;

    @Test
    @Timeout(value = 8, unit = TimeUnit.MINUTES)
    void bareSolrCloud7BackupInS3ProducesCorrectMappings() throws Exception {
        createCollectionWithSchema();
        indexOneDoc();
        var localBackup = backupAndCopyToHost();
        uploadVerbatimToS3(localBackup, SNAPSHOT_NAME);

        try (var target = new SearchClusterContainer(SearchClusterContainer.OS_V2_19_4)) {
            CompletableFuture.runAsync(target::start).join();

            var s3LocalDir = tempDir.resolve("s3-download");
            Files.createDirectories(s3LocalDir);

            var metaArgs = new MigrateOrEvaluateArgs();
            metaArgs.sourceVersion = Version.fromString("SOLR " + SolrClusterContainer.SOLR_7.tag());
            metaArgs.snapshotName = SNAPSHOT_NAME;
            metaArgs.s3RepoUri = "s3://" + BUCKET_NAME;
            metaArgs.s3Region = REGION;
            metaArgs.s3Endpoint = LOCAL_STACK.getEndpoint().toString();
            metaArgs.s3LocalDirPath = s3LocalDir.toString();
            metaArgs.targetArgs.host = target.getUrl();

            var metadataContext = MetadataMigrationTestContext.factory().noOtelTracking();
            var result = new MetadataMigration().migrate(metaArgs).execute(metadataContext);
            log.atInfo().setMessage("Metadata migration result: {}").addArgument(result.asCliOutput()).log();
            assertThat("metadata migration of the bare S3 backup should succeed",
                result.getExitCode(), equalTo(0));

            // Index name is recovered from backup.properties (not snapshot.shardN), with correct mappings.
            var targetOps = new ClusterOperations(target);
            var mappingResp = targetOps.get("/" + COLLECTION + "/_mapping");
            assertThat("target index '" + COLLECTION + "' should be created", mappingResp.getKey(), equalTo(200));

            var properties = MAPPER.readTree(mappingResp.getValue())
                .path(COLLECTION).path("mappings").path("properties");
            assertThat("title → keyword", properties.path("title").path("type").asText(), equalTo("keyword"));
            assertThat("count → integer", properties.path("count").path("type").asText(), equalTo("integer"));
            assertThat("created → date", properties.path("created").path("type").asText(), equalTo("date"));
            assertThat("description → text", properties.path("description").path("type").asText(), equalTo("text"));
            assertThat("active → boolean", properties.path("active").path("type").asText(), equalTo("boolean"));
        }
    }

    // ---- Solr backup → host ----

    private void createCollectionWithSchema() throws Exception {
        SOLR_CLOUD.execInContainer("curl", "-s",
            "http://localhost:8983/solr/admin/collections?action=CREATE"
                + "&name=" + COLLECTION
                + "&numShards=1"
                + "&replicationFactor=1"
                + "&maxShardsPerNode=1"
                + "&wt=json");
        waitForCollectionActive();
        addField("title", "string");
        addField("count", "pint");
        addField("created", "pdate");
        addField("description", "text_general");
        addField("active", "boolean");
    }

    private void addField(String name, String type) throws Exception {
        SOLR_CLOUD.execInContainer("curl", "-s",
            "http://localhost:8983/solr/" + COLLECTION + "/schema",
            "-H", "Content-Type: application/json",
            "-d", "{\"add-field\":{\"name\":\"" + name + "\",\"type\":\"" + type + "\",\"stored\":true}}");
    }

    private void indexOneDoc() throws Exception {
        SOLR_CLOUD.execInContainer("curl", "-s",
            "http://localhost:8983/solr/" + COLLECTION + "/update?commit=true",
            "-H", "Content-Type: application/json",
            "-d", "[{\"id\":\"1\",\"title\":\"test\",\"count\":42,"
                + "\"created\":\"2024-01-01T00:00:00Z\","
                + "\"description\":\"hello world\",\"active\":true}]");
    }

    private void waitForCollectionActive() throws Exception {
        for (int i = 0; i < 60; i++) {
            var status = SOLR_CLOUD.execInContainer("curl", "-s",
                "http://localhost:8983/solr/admin/collections?action=CLUSTERSTATUS&collection="
                    + COLLECTION + "&wt=json");
            if (status.getStdout().contains("\"state\":\"active\"")) {
                return;
            }
            Thread.sleep(1000);
        }
        throw new IllegalStateException("Collection " + COLLECTION + " did not become active");
    }

    private Path backupAndCopyToHost() throws Exception {
        var probe = SOLR_CLOUD.execInContainer("sh", "-c",
            "for d in /var/solr/data /opt/solr/server/solr; do "
            + "  if [ -d \"$d\" ] && [ -w \"$d\" ]; then echo \"$d\"; break; fi; done");
        var solrDataDir = probe.getStdout().trim();
        if (solrDataDir.isEmpty()) {
            throw new IllegalStateException("No writable Solr data directory found in container");
        }
        var backupLocation = solrDataDir + "/backups";
        SOLR_CLOUD.execInContainer("mkdir", "-p", backupLocation);

        var backup = SOLR_CLOUD.execInContainer("curl", "-s",
            "http://localhost:8983/solr/admin/collections?action=BACKUP"
                + "&name=" + SNAPSHOT_NAME
                + "&collection=" + COLLECTION
                + "&location=" + backupLocation
                + "&wt=json");
        if (backup.getStdout().contains("\"status\":500") || backup.getStdout().contains("\"status\":400")) {
            throw new IllegalStateException("BACKUP failed: " + backup.getStdout());
        }

        var containerBackupDir = backupLocation + "/" + SNAPSHOT_NAME;
        var localBackupRoot = tempDir.resolve("bare_backup");
        Files.createDirectories(localBackupRoot);
        var find = SOLR_CLOUD.execInContainer("find", containerBackupDir, "-type", "f");
        for (var line : find.getStdout().trim().split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            var rel = line.substring(containerBackupDir.length()).replaceFirst("^/", "");
            var localFile = localBackupRoot.resolve(rel);
            Files.createDirectories(localFile.getParent());
            SOLR_CLOUD.copyFileFromContainer(line, localFile.toString());
        }
        return localBackupRoot;
    }

    // ---- host → S3 (verbatim, no reshape) ----

    private void uploadVerbatimToS3(Path localBackupRoot, String snapshotName) throws Exception {
        var containerPath = "/tmp/" + snapshotName;
        LOCAL_STACK.copyFileToContainer(
            MountableFile.forHostPath(localBackupRoot.toString()), containerPath);
        LOCAL_STACK.execInContainer("awslocal", "s3", "cp", "--recursive",
            containerPath, "s3://" + BUCKET_NAME + "/" + snapshotName + "/");
    }
}
