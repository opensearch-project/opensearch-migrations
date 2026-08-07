package org.opensearch.migrations;

import java.util.List;

import org.opensearch.migrations.bulkload.common.DeltaMode;
import org.opensearch.migrations.bulkload.pipeline.provider.EsSnapshotSourceProvider;
import org.opensearch.migrations.bulkload.pipeline.provider.EsSnapshotSpec;
import org.opensearch.migrations.bulkload.pipeline.provider.SolrBackupSourceProvider;
import org.opensearch.migrations.bulkload.pipeline.provider.SolrBackupSpec;
import org.opensearch.migrations.bulkload.pipeline.spi.DocumentSourceRegistry;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;

/**
 * Guards the assembled application: a missing {@code META-INF/services} entry unit-tests clean and
 * then finds zero providers at runtime, so discovery itself has to be asserted.
 */
class DocumentSourceDiscoveryTest {

    @Test
    void serviceLoaderFindsEveryShippedProvider() {
        var registry = DocumentSourceRegistry.fromServiceLoader();

        assertThat(registry.kinds(), containsInAnyOrder(
            EsSnapshotSourceProvider.KIND, SolrBackupSourceProvider.KIND));
        assertThat(registry.resolve(EsSnapshotSourceProvider.KIND),
            instanceOf(EsSnapshotSourceProvider.class));
        assertThat(registry.resolve(SolrBackupSourceProvider.KIND),
            instanceOf(SolrBackupSourceProvider.class));
    }

    @Test
    void selectSource_choosesTheSolrProviderForASolrSourceVersion() {
        var args = new RfsMigrateDocuments.Args();
        args.sourceVersion = Version.fromString("SOLR 8.11.2");
        args.repoUri = "s3://bucket/backups";
        args.snapshotName = "nightly";
        args.s3Region = "us-east-2";
        args.indexAllowlist = List.of("catalog");

        var selection = RfsMigrateDocuments.selectSource(args, false);

        assertThat(selection.kind(), equalTo(SolrBackupSourceProvider.KIND));
        var spec = new SolrBackupSourceProvider().parseSpec(selection.config());
        assertThat(spec, equalTo(new SolrBackupSpec(
            "s3://bucket/backups", "nightly", 8, List.of("catalog"), "us-east-2", null)));
    }

    @Test
    void selectSource_choosesTheSnapshotProviderOtherwise() {
        var args = new RfsMigrateDocuments.Args();
        args.sourceVersion = Version.fromString("ES 7.10.2");
        args.repoUri = "file:///snapshots";
        args.snapshotName = "nightly";
        args.maxShardSizeBytes = 1234L;
        args.experimental.useRecoverySource = true;
        args.experimental.previousSnapshotName = "previous";
        args.experimental.experimentalDeltaMode = DeltaMode.UPDATES_ONLY;

        var selection = RfsMigrateDocuments.selectSource(args, true);

        assertThat(selection.kind(), equalTo(EsSnapshotSourceProvider.KIND));
        var spec = new EsSnapshotSourceProvider().parseSpec(selection.config());
        assertThat(spec, equalTo(new EsSnapshotSpec(
            "file:///snapshots", "nightly", Version.fromString("ES 7.10.2"), List.of(),
            null, null, false, 1234L, true, true, "previous", DeltaMode.UPDATES_ONLY, false)));
    }
}
