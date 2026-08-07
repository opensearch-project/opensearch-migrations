package org.opensearch.migrations;

import java.nio.file.Files;
import java.nio.file.Path;

import org.opensearch.migrations.bulkload.common.DeltaMode;
import org.opensearch.migrations.bulkload.pipeline.provider.EsSnapshotSourceProvider;
import org.opensearch.migrations.bulkload.pipeline.provider.SolrBackupSourceProvider;

import com.beust.jcommander.ParameterException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers {@code --source-kind}/{@code --source-config}. Inference must stay untouched when neither
 * is given, so existing invocations keep working.
 */
class ExplicitSourceSelectionTest {

    private static final String SOLR_CONFIG =
        "{\"repoUri\":\"file:///backups\",\"backupName\":\"nightly\",\"solrMajorVersion\":8}";

    private static final String ES_CONFIG =
        "{\"repoUri\":\"file:///snapshots\",\"snapshotName\":\"nightly\",\"version\":\"ES 7.10.2\"}";

    private static RfsMigrateDocuments.Args argsWithExplicitSource(String kind, String config) {
        var args = new RfsMigrateDocuments.Args();
        args.sourceKind = kind;
        args.sourceConfig = config;
        return args;
    }

    @Test
    void explicitKindWinsOverInference() {
        var args = argsWithExplicitSource(SolrBackupSourceProvider.KIND, SOLR_CONFIG);
        // A version that inference would read as an ES snapshot.
        args.sourceVersion = null;

        var selection = RfsMigrateDocuments.selectSource(args, false);

        assertThat(selection.kind(), equalTo(SolrBackupSourceProvider.KIND));
        assertThat(selection.config().get("backupName").asText(), equalTo("nightly"));
    }

    @Test
    void inferenceIsUnchangedWhenNeitherArgumentIsGiven() {
        var args = new RfsMigrateDocuments.Args();
        args.sourceVersion = Version.fromString("ES 7.10.2");
        args.repoUri = "file:///snapshots";
        args.snapshotName = "nightly";

        var selection = RfsMigrateDocuments.selectSource(args, false);

        assertThat(selection.kind(), equalTo(EsSnapshotSourceProvider.KIND));
    }

    @Test
    void sourceConfigReadsInlineJson() {
        var config = RfsMigrateDocuments.readSourceConfig(SOLR_CONFIG);

        assertThat(config.get("repoUri").asText(), equalTo("file:///backups"));
    }

    @Test
    void sourceConfigReadsAFileWhenPrefixedWithAt(@TempDir Path tempDir) throws Exception {
        var file = tempDir.resolve("source.json");
        Files.writeString(file, SOLR_CONFIG);

        var config = RfsMigrateDocuments.readSourceConfig("@" + file);

        assertThat(config.get("backupName").asText(), equalTo("nightly"));
    }

    @Test
    void sourceConfigReportsUnreadableJson() {
        assertThat(assertThrows(ParameterException.class,
            () -> RfsMigrateDocuments.readSourceConfig("not json")).getMessage(),
            containsString("--source-config"));
    }

    @Test
    void sourceConfigReportsAMissingFile(@TempDir Path tempDir) {
        var missing = tempDir.resolve("absent.json");

        assertThat(assertThrows(ParameterException.class,
            () -> RfsMigrateDocuments.readSourceConfig("@" + missing)).getMessage(),
            containsString("--source-config"));
    }

    @Test
    void eitherArgumentWithoutTheOtherIsRejected() {
        assertThat(assertThrows(ParameterException.class, () -> RfsMigrateDocuments.validateSourceSelection(
            argsWithExplicitSource(SolrBackupSourceProvider.KIND, null))).getMessage(),
            containsString("must be given together"));
        assertThat(assertThrows(ParameterException.class, () -> RfsMigrateDocuments.validateSourceSelection(
            argsWithExplicitSource(null, SOLR_CONFIG))).getMessage(),
            containsString("must be given together"));
    }

    @Test
    void supersededArgumentsAreRejectedRatherThanIgnored() {
        var args = argsWithExplicitSource(SolrBackupSourceProvider.KIND, SOLR_CONFIG);
        args.repoUri = "file:///backups";
        args.snapshotName = "nightly";

        var message = assertThrows(ParameterException.class,
            () -> RfsMigrateDocuments.validateSourceSelection(args)).getMessage();

        assertThat(message, containsString("--repo-uri"));
        assertThat(message, containsString("--snapshot-name"));
    }

    @Test
    void anUnknownKindIsRejectedAndListsTheAvailableOnes() {
        var message = assertThrows(ParameterException.class,
            () -> RfsMigrateDocuments.validateSourceSelection(
                argsWithExplicitSource("not-a-kind", SOLR_CONFIG))).getMessage();

        assertThat(message, containsString("not-a-kind"));
        assertThat(message, containsString(EsSnapshotSourceProvider.KIND));
    }

    @Test
    void aKindIsAcceptedRegardlessOfCaseOrSurroundingSpace() {
        assertDoesNotThrow(() -> RfsMigrateDocuments.validateSourceSelection(
            argsWithExplicitSource("  Solr-Backup  ", SOLR_CONFIG)));
    }

    @Test
    void neitherArgumentLeavesTheExistingValidationInPlace() {
        var args = new RfsMigrateDocuments.Args();

        // No source kind, so the per-source checks still run and still complain.
        assertThat(assertThrows(ParameterException.class,
            () -> RfsMigrateDocuments.validateArgs(args)).getMessage(),
            containsString("--snapshot-name"));
    }

    @Test
    void supersededSpecOnlyArgumentsAreRejected() {
        var args = argsWithExplicitSource(EsSnapshotSourceProvider.KIND, ES_CONFIG);
        args.experimental.experimentalDeltaMode = DeltaMode.UPDATES_ONLY;
        args.experimental.useRecoverySource = true;
        args.maxShardSizeBytes = 1024L;

        var message = assertThrows(ParameterException.class,
            () -> RfsMigrateDocuments.validateSourceSelection(args)).getMessage();

        assertThat(message, containsString("--experimental-delta-mode"));
        assertThat(message, containsString("--use-recovery-source"));
        assertThat(message, containsString("--max-shard-size-bytes"));
    }

    @Test
    void workingDirectoriesAreStillRequiredUnderExplicitSelection() {
        var args = argsWithExplicitSource(EsSnapshotSourceProvider.KIND, ES_CONFIG);

        assertThat(assertThrows(ParameterException.class,
            () -> RfsMigrateDocuments.validateArgs(args)).getMessage(),
            containsString("--lucene-dir"));
    }

    @Test
    void aSolrBackupStillNeedsACoordinatorHost() {
        var args = argsWithExplicitSource(SolrBackupSourceProvider.KIND, SOLR_CONFIG);
        args.luceneDir = "/tmp/lucene";

        assertThat(assertThrows(ParameterException.class,
            () -> RfsMigrateDocuments.validateArgs(args)).getMessage(),
            containsString("--coordinator-host"));
    }

    @Test
    void aSolrBackupDoesNotNeedAWorkingDirectory() {
        var args = argsWithExplicitSource(SolrBackupSourceProvider.KIND, SOLR_CONFIG);
        args.coordinatorArgs.host = "http://coordinator:9200";

        // Reads the backup in place, matching the inferred Solr path.
        assertDoesNotThrow(() -> RfsMigrateDocuments.validateArgs(args));
    }

    @Test
    void eitherWorkingDirectorySatisfiesASnapshot() {
        var withLocalDir = argsWithExplicitSource(EsSnapshotSourceProvider.KIND, ES_CONFIG);
        withLocalDir.localDir = "/tmp/local";

        assertDoesNotThrow(() -> RfsMigrateDocuments.validateArgs(withLocalDir));
    }

    @Test
    void sourceConfigIsReportedRatherThanNullPointing() {
        assertThat(assertThrows(ParameterException.class,
            () -> RfsMigrateDocuments.readSourceConfig(null)).getMessage(),
            containsString("--source-config"));
    }
}
