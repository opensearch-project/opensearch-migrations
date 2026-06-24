package org.opensearch.migrations.bulkload.solr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.opensearch.migrations.bulkload.solr.SolrBackupLayout.BareBackupLayout;
import org.opensearch.migrations.bulkload.solr.SolrBackupLayout.SolrBackupMode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Pins down the two real Solr 7 backup directory shapes and proves the Migration Assistant
 * classifies each correctly <em>without</em> the manual reshape step (issue #3147).
 *
 * <p>These are synthetic fixtures: the exact trees are built by hand here so the contract is
 * documented in code. Container tests (real Solr 7 BACKUP output) live separately and must agree
 * with these shapes — a divergence there means a fixture here is wrong.
 */
class SolrBareBackupLayoutTest {

    @TempDir
    Path tempDir;

    private static final String COLLECTION = "nyc_taxis";

    // ---- fixture builders: the exact on-disk trees real Solr 7 produces ----

    /**
     * SolrCloud 7 (Collections-API BACKUP), written directly to the backup root with NO collection
     * wrapper directory:
     * <pre>
     *   &lt;root&gt;/
     *   ├── backup.properties        (collection=nyc_taxis)
     *   ├── snapshot.shard1/segments_1
     *   ├── snapshot.shard2/segments_1
     *   └── zk_backup/configs/nyc_taxis_configs/
     * </pre>
     */
    private Path buildSolrCloud7Backup(String collection) throws IOException {
        var root = tempDir.resolve("cloud_backup_root");
        Files.createDirectories(root);
        Files.writeString(root.resolve("backup.properties"),
            "collection=" + collection + "\n"
                + "collection.configName=" + collection + "_configs\n"
                + "index.version=7.7.3\n");
        for (int shard = 1; shard <= 2; shard++) {
            var shardDir = root.resolve("snapshot.shard" + shard);
            Files.createDirectories(shardDir);
            Files.createFile(shardDir.resolve("segments_1"));
        }
        Files.createDirectories(root.resolve("zk_backup/configs/" + collection + "_configs"));
        return root;
    }

    /**
     * Standalone Solr 7 (replication handler command=backup): a single flat Lucene index inside a
     * {@code snapshot.<backupName>/} directory, with NO backup.properties / zk_backup / shard dirs:
     * <pre>
     *   &lt;root&gt;/
     *   └── snapshot.nyc_taxis/
     *       ├── segments_1
     *       └── _0.cfs
     * </pre>
     */
    private Path buildStandalone7Backup(String backupName) throws IOException {
        var root = tempDir.resolve("standalone_backup_root");
        var snapshotDir = root.resolve("snapshot." + backupName);
        Files.createDirectories(snapshotDir);
        Files.createFile(snapshotDir.resolve("segments_1"));
        Files.createFile(snapshotDir.resolve("_0.cfs"));
        return root;
    }

    // ---- SolrCloud 7 ----

    @Test
    void solrCloud7BareLayout_classifiedAsCloud_nameFromBackupProperties() throws IOException {
        var root = buildSolrCloud7Backup(COLLECTION);

        var layout = SolrBackupLayout.classifyBareBackup(root, null);

        assertThat(layout, notNullValue());
        assertThat("SolrCloud markers (zk_backup/backup.properties) => CLOUD",
            layout.mode(), equalTo(SolrBackupMode.CLOUD));
        assertThat("collection name recovered from backup.properties",
            layout.collectionName(), equalTo(COLLECTION));
        // Data lives at the root itself — no wrapper directory.
        assertThat(layout.dataPath(), equalTo(""));
        assertThat(layout.resolveFrom(root), equalTo(root));
    }

    @Test
    void solrCloud7BareLayout_shardsCountedAsOneCollection_notSeparateCollections() throws IOException {
        var root = buildSolrCloud7Backup(COLLECTION);

        var layout = SolrBackupLayout.classifyBareBackup(root, null);

        // The two snapshot.shardN dirs are shards of ONE collection, not two collections.
        assertThat(SolrBackupLayout.countShards(layout.resolveFrom(root)), equalTo(2));
    }

    @Test
    void solrCloud7BareLayout_nameOverrideWins() throws IOException {
        var root = buildSolrCloud7Backup(COLLECTION);

        var layout = SolrBackupLayout.classifyBareBackup(root, "custom_index");

        assertThat(layout.mode(), equalTo(SolrBackupMode.CLOUD));
        assertThat(layout.collectionName(), equalTo("custom_index"));
    }

    // ---- Standalone 7 ----

    @Test
    void standalone7Layout_classifiedAsStandalone_nameStrippedFromSnapshotDir() throws IOException {
        var root = buildStandalone7Backup(COLLECTION);

        var layout = SolrBackupLayout.classifyBareBackup(root, null);

        assertThat(layout, notNullValue());
        assertThat("no SolrCloud markers => STANDALONE",
            layout.mode(), equalTo(SolrBackupMode.STANDALONE));
        assertThat("core name = snapshot.<name> with prefix stripped",
            layout.collectionName(), equalTo(COLLECTION));
        // Data lives one level down, inside the snapshot.<name>/ directory.
        assertThat(layout.dataPath(), equalTo("snapshot." + COLLECTION));
        assertThat(layout.resolveFrom(root), equalTo(root.resolve("snapshot." + COLLECTION)));
    }

    @Test
    void standalone7Layout_singleShard() throws IOException {
        var root = buildStandalone7Backup(COLLECTION);

        var layout = SolrBackupLayout.classifyBareBackup(root, null);

        assertThat(SolrBackupLayout.countShards(layout.resolveFrom(root)), equalTo(1));
    }

    @Test
    void standalone7Layout_nameOverrideWins() throws IOException {
        // The backup name need not match the desired index name; the override covers that.
        var root = buildStandalone7Backup("backup_2024_01_15");

        var layout = SolrBackupLayout.classifyBareBackup(root, COLLECTION);

        assertThat(layout.mode(), equalTo(SolrBackupMode.STANDALONE));
        assertThat(layout.collectionName(), equalTo(COLLECTION));
    }

    // ---- the discriminator: both shapes fed side-by-side, must be told apart ----

    @Test
    void discriminator_cloudAndStandalone_areClassifiedDifferently() throws IOException {
        var cloudRoot = buildSolrCloud7Backup(COLLECTION);
        var standaloneRoot = buildStandalone7Backup(COLLECTION);

        var cloud = SolrBackupLayout.classifyBareBackup(cloudRoot, null);
        var standalone = SolrBackupLayout.classifyBareBackup(standaloneRoot, null);

        // Same collection name, both use snapshot.* directories — only the markers tell them apart.
        assertThat(cloud.mode(), equalTo(SolrBackupMode.CLOUD));
        assertThat(standalone.mode(), equalTo(SolrBackupMode.STANDALONE));
        assertThat(cloud.mode(), not(equalTo(standalone.mode())));
        // The standalone snapshot.<name> is never read as a 1-shard cloud collection, and the cloud
        // snapshot.shardN dirs are never read as standalone indexes.
        assertThat(cloud.collectionName(), equalTo(COLLECTION));
        assertThat(standalone.collectionName(), equalTo(COLLECTION));
    }

    // ---- negative: a wrapped multi-collection layout is NOT a bare backup ----

    @Test
    void wrappedCollectionLayout_isNotBare_returnsNull() throws IOException {
        // Solr 8 style: <root>/<collection>/{zk_backup_0, ...} — the collection wrapper exists, so
        // this is the already-supported layout and must not be misread as a bare backup.
        var root = tempDir.resolve("wrapped_root");
        Files.createDirectories(root.resolve(COLLECTION + "/zk_backup_0/configs/cfg"));
        Files.createDirectories(root.resolve(COLLECTION + "/index"));

        assertThat(SolrBackupLayout.classifyBareBackup(root, null), nullValue());
    }

    @Test
    void nonExistentRoot_returnsNull() {
        assertThat(SolrBackupLayout.classifyBareBackup(tempDir.resolve("does_not_exist"), null), nullValue());
    }
}
