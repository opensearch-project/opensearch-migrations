package org.opensearch.migrations;

import java.util.List;

import org.opensearch.migrations.bulkload.common.DeltaMode;
import org.opensearch.migrations.bulkload.pipeline.provider.EsSnapshotSourceProvider;
import org.opensearch.migrations.bulkload.pipeline.provider.EsSnapshotSourceSpec;
import org.opensearch.migrations.bulkload.pipeline.provider.SolrBackupSourceProvider;
import org.opensearch.migrations.bulkload.pipeline.provider.SolrBackupSourceSpec;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Covers the inference from the legacy per-source flags to a provider and its spec, which
 * {@code --source-kind}/{@code --source-config} supersede. Deleted along with the flags in phase 3.
 */
class LegacySourceSelectionTest {

    @Test
    void selectSource_choosesTheSolrProviderForASolrSourceVersion() {
        var args = new RfsMigrateDocuments.Args();
        args.legacySource.sourceVersion = Version.fromString("SOLR 8.11.2");
        args.legacySource.repoUri = "s3://bucket/backups";
        args.legacySource.snapshotName = "nightly";
        args.legacySource.s3Region = "us-east-2";
        args.indexAllowlist = List.of("catalog");

        var selection = RfsMigrateDocuments.selectSource(args, false);

        assertThat(selection.kind(), equalTo(SolrBackupSourceProvider.KIND));
        var spec = new SolrBackupSourceProvider().parseSpec(selection.config());
        assertThat(spec, equalTo(new SolrBackupSourceSpec(
            "s3://bucket/backups", "nightly", 8, List.of("catalog"), "us-east-2", null)));
    }

    @Test
    void selectSource_choosesTheSnapshotProviderOtherwise() {
        var args = new RfsMigrateDocuments.Args();
        args.legacySource.sourceVersion = Version.fromString("ES 7.10.2");
        args.legacySource.repoUri = "file:///snapshots";
        args.legacySource.snapshotName = "nightly";
        args.legacySource.maxShardSizeBytes = 1234L;
        args.legacySource.useRecoverySource = true;
        args.legacySource.previousSnapshotName = "previous";
        args.legacySource.experimentalDeltaMode = DeltaMode.UPDATES_ONLY;

        var selection = RfsMigrateDocuments.selectSource(args, true);

        assertThat(selection.kind(), equalTo(EsSnapshotSourceProvider.KIND));
        var spec = new EsSnapshotSourceProvider().parseSpec(selection.config());
        assertThat(spec, equalTo(new EsSnapshotSourceSpec(
            "file:///snapshots", "nightly", Version.fromString("ES 7.10.2"), List.of(),
            null, null, false, 1234L, true, true, "previous", DeltaMode.UPDATES_ONLY, false)));
    }
}
