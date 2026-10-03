package org.opensearch.migrations;

import java.nio.file.Files;
import java.nio.file.Path;

import org.opensearch.migrations.bulkload.solr.SolrMultiCollectionSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;

/**
 * In-process coverage for {@link RfsMigrateDocuments#buildSolrDocumentSource}, which otherwise only
 * runs inside the forked migration subprocess. Exercises the filesystem path (no S3 required).
 */
class RfsSolrDocumentSourceTest {

    @TempDir
    Path backupDir;

    private RfsMigrateDocuments.Args solr7Args() {
        var args = new RfsMigrateDocuments.Args();
        args.sourceVersion = Version.fromString("SOLR 7.7.3");
        return args;
    }

    @Test
    void buildSolrDocumentSource_bareCloudFilesystemBackup() throws Exception {
        Files.writeString(backupDir.resolve("backup.properties"), "collection=nyc_taxis\n");
        var shard = Files.createDirectories(backupDir.resolve("snapshot.shard1"));
        Files.createFile(shard.resolve("segments_1"));
        Files.createDirectories(backupDir.resolve("zk_backup/configs/nyc_taxis_configs"));

        SolrMultiCollectionSource source =
            RfsMigrateDocuments.buildSolrDocumentSource(solr7Args(), backupDir, null);

        assertThat(source, notNullValue());
    }

    @Test
    void buildSolrDocumentSource_standaloneFilesystemBackup() throws Exception {
        var snapshotDir = Files.createDirectories(backupDir.resolve("snapshot.catalog"));
        Files.createFile(snapshotDir.resolve("segments_1"));

        SolrMultiCollectionSource source =
            RfsMigrateDocuments.buildSolrDocumentSource(solr7Args(), backupDir, null);

        assertThat(source, notNullValue());
    }
}
