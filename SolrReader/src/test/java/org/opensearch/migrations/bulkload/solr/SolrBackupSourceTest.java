package org.opensearch.migrations.bulkload.solr;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.opensearch.migrations.bulkload.common.SnapshotReadFailure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.test.StepVerifier;
import shadow.lucene9.org.apache.lucene.analysis.core.KeywordAnalyzer;
import shadow.lucene9.org.apache.lucene.document.Field;
import shadow.lucene9.org.apache.lucene.document.StringField;
import shadow.lucene9.org.apache.lucene.index.IndexWriter;
import shadow.lucene9.org.apache.lucene.index.IndexWriterConfig;
import shadow.lucene9.org.apache.lucene.store.FSDirectory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

class SolrBackupSourceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void discoversSingleShardFlatBackup() throws IOException {
        // Flat backup: segments_N at top level
        Files.createFile(tempDir.resolve("segments_1"));
        Files.createFile(tempDir.resolve("_0.cfs"));

        var source = new SolrBackupSource(tempDir, "test", emptySchema(), 8);
        var partitions = source.listPartitions("test");

        assertThat("Single flat shard", partitions.size(), equalTo(1));
        assertThat(partitions.get(0).collectionName(), equalTo("test"));
    }

    @Test
    void discoversMultiShardDirectories() throws IOException {
        // Multi-shard: shard1/ and shard2/ each with segments_N
        var shard1 = tempDir.resolve("shard1");
        var shard2 = tempDir.resolve("shard2");
        Files.createDirectories(shard1);
        Files.createDirectories(shard2);
        Files.createFile(shard1.resolve("segments_1"));
        Files.createFile(shard2.resolve("segments_2"));

        var source = new SolrBackupSource(tempDir, "test", emptySchema(), 8);
        var partitions = source.listPartitions("test");

        assertThat("Two shards discovered", partitions.size(), equalTo(2));
    }

    @Test
    void discoversSolrCloudShardStructure() throws IOException {
        // SolrCloud: shard1/data/index/segments_N
        var indexDir1 = tempDir.resolve("shard1").resolve("data").resolve("index");
        var indexDir2 = tempDir.resolve("shard2").resolve("data").resolve("index");
        Files.createDirectories(indexDir1);
        Files.createDirectories(indexDir2);
        Files.createFile(indexDir1.resolve("segments_1"));
        Files.createFile(indexDir2.resolve("segments_1"));

        var source = new SolrBackupSource(tempDir, "test", emptySchema(), 8);
        var partitions = source.listPartitions("test");

        assertThat("Two SolrCloud shards", partitions.size(), equalTo(2));
    }

    @Test
    void discoversSolr6SnapshotShardDirectories() throws IOException {
        // Solr 6 SolrCloud BACKUP produces snapshot.shardN/ dirs (one per shard),
        // each containing Lucene segment files directly.
        var shard1 = tempDir.resolve("snapshot.shard1");
        var shard2 = tempDir.resolve("snapshot.shard2");
        Files.createDirectories(shard1);
        Files.createDirectories(shard2);
        Files.createFile(shard1.resolve("segments_1"));
        Files.createFile(shard1.resolve("_0.cfs"));
        Files.createFile(shard2.resolve("segments_1"));
        Files.createFile(shard2.resolve("_0.nvm"));
        Files.createFile(tempDir.resolve("backup.properties"));

        var source = new SolrBackupSource(tempDir, "test", emptySchema(), 6);
        var partitions = source.listPartitions("test");

        assertThat("Two Solr 6 snapshot shards discovered", partitions.size(), equalTo(2));
        assertThat(partitions.get(0).collectionName(), equalTo("test"));
    }

    @Test
    void discoversSolr6SnapshotShardStubDirs() throws IOException {
        // collectionPreparer creates empty snapshot.shardN/ stubs before shardPreparer
        // downloads the actual index files. discoverShardDirs must count them anyway.
        Files.createDirectories(tempDir.resolve("snapshot.shard1"));
        Files.createDirectories(tempDir.resolve("snapshot.shard2"));
        Files.createFile(tempDir.resolve("backup.properties"));

        var source = new SolrBackupSource(tempDir, "test", emptySchema(), 6);
        var partitions = source.listPartitions("test");

        assertThat("Empty snapshot stubs still count as shards", partitions.size(), equalTo(2));
    }

    @Test
    void solr6SnapshotPartitionsHaveNoFileNameMapping() throws IOException {
        // Solr 6 backups use plain Lucene filenames — no UUID mapping should be set.
        var shard1 = tempDir.resolve("snapshot.shard1");
        Files.createDirectories(shard1);
        Files.createFile(shard1.resolve("segments_1"));

        var source = new SolrBackupSource(tempDir, "test", emptySchema(), 6);
        var partitions = source.listPartitions("test");

        assertThat(partitions.size(), equalTo(1));
        var solrPartition = (SolrShardPartition) partitions.get(0);
        assertThat("No UUID mapping for Solr 6", solrPartition.fileNameMapping(), org.hamcrest.CoreMatchers.nullValue());
    }

    @Test
    void returnsEmptyPartitionsForNonExistentBackupDir() {
        // only backup.properties in S3, local dir never created.
        var missingDir = tempDir.resolve("nonexistent_collection");
        var source = new SolrBackupSource(missingDir, "test", emptySchema(), 6);
        var partitions = source.listPartitions("test");
        assertThat("No partitions for missing backup dir", partitions.size(), equalTo(0));
    }

    @Test
    void throwsOnEmptyDir() {
        var source = new SolrBackupSource(tempDir, "test", emptySchema(), 8);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
            () -> source.listPartitions("test"));
    }

    @Test
    void hasSegmentsFileSurfacesClassifiedFailureWhenListFails() throws IOException {
        // An I/O error while listing the backup dir (via hasSegmentsFile) must surface as a classified SolrBackupReadException.
        Files.createDirectories(tempDir.resolve("backup"));
        var backupDir = tempDir.resolve("backup");
        var source = new SolrBackupSource(backupDir, "test", emptySchema(), 8);

        try (var mockedFiles = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            mockedFiles.when(() -> Files.list(backupDir)).thenThrow(new IOException("simulated I/O error"));

            var ex = assertThrows(SolrBackupReadException.class, () -> source.listPartitions("test"));
            assertThat(ex, instanceOf(SnapshotReadFailure.class));
            assertThat(ex.getMessage(), org.hamcrest.Matchers.containsString("Failed to list directory"));
            assertThat("the underlying IOException is preserved", ex.getCause(), instanceOf(IOException.class));
        }
    }

    @Test
    void discoverShardDirsSurfacesClassifiedFailureWhenBackupListingFails() throws IOException {
        // An I/O error while enumerating shard subdirectories must surface as a classified SolrBackupReadException.
        Files.createDirectories(tempDir.resolve("backup"));
        var backupDir = tempDir.resolve("backup");
        var source = new SolrBackupSource(backupDir, "test", emptySchema(), 8);

        try (var mockedFiles = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            mockedFiles.when(() -> Files.list(backupDir))
                .thenReturn(java.util.stream.Stream.of())          // hasSegmentsFile probe: no segments
                .thenThrow(new IOException("simulated I/O error")); // shard enumeration fails

            var ex = assertThrows(SolrBackupReadException.class, () -> source.listPartitions("test"));
            assertThat(ex, instanceOf(SnapshotReadFailure.class));
            assertThat(ex.getMessage(), org.hamcrest.Matchers.containsString("Failed to list backup directory"));
            assertThat("the underlying IOException is preserved", ex.getCause(), instanceOf(IOException.class));
        }
    }

    @Test
    void findSegmentsFileSurfacesClassifiedFailureWhenListFails() throws IOException {
        // An I/O error while listing the index dir to locate segments_N must surface as a classified SolrBackupReadException.
        var indexDir = tempDir.resolve("idx");
        Files.createDirectories(indexDir);
        var partition = new SolrShardPartition("test", "shard1", indexDir);
        var source = new SolrBackupSource(tempDir, "test", emptySchema(), 8);

        try (var mockedFiles = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            mockedFiles.when(() -> Files.list(indexDir)).thenThrow(new IOException("simulated I/O error"));

            var ex = assertThrows(SolrBackupReadException.class, () -> source.readDocuments(partition, null));
            assertThat(ex, instanceOf(SnapshotReadFailure.class));
            assertThat(ex.getMessage(), org.hamcrest.Matchers.containsString("Failed to list directory"));
            assertThat("the underlying IOException is preserved", ex.getCause(), instanceOf(IOException.class));
        }
    }

    @Test
    void throwsClassifiedFailureOnUnreadableShardMetadata() throws IOException {
        // shard_backup_metadata/ present (so the UUID path is taken) but the metadata JSON is
        // corrupt: the read failure must surface as a classified SolrBackupReadException, carrying
        // the underlying cause, rather than an unclassified exception.
        var metadataDir = tempDir.resolve("shard_backup_metadata");
        Files.createDirectories(metadataDir);
        Files.writeString(metadataDir.resolve("md_shard1_0.json"), "{ not valid json");

        var source = new SolrBackupSource(tempDir, "test", emptySchema(), 8);
        var ex = assertThrows(SolrBackupReadException.class, () -> source.listPartitions("test"));
        assertThat(ex, org.hamcrest.Matchers.instanceOf(
            org.opensearch.migrations.bulkload.common.SnapshotReadFailure.class));
        assertThat(ex.getCause() != null, equalTo(true));
    }

    @Test
    void metadataReflectsShardCount() throws IOException {
        var shard1 = tempDir.resolve("shard1");
        var shard2 = tempDir.resolve("shard2");
        var shard3 = tempDir.resolve("shard3");
        Files.createDirectories(shard1);
        Files.createDirectories(shard2);
        Files.createDirectories(shard3);
        Files.createFile(shard1.resolve("segments_1"));
        Files.createFile(shard2.resolve("segments_1"));
        Files.createFile(shard3.resolve("segments_1"));

        var source = new SolrBackupSource(tempDir, "test", emptySchema(), 8);
        var metadata = source.readCollectionMetadata("test");

        assertThat("Partition count matches shards", metadata.partitionCount(), equalTo(3));
    }

    @ParameterizedTest
    @ValueSource(ints = {6, 7, 8, 9})
    void acceptsSupportedSolrMajorVersions(int major) throws IOException {
        // Construction succeeds; reader selection happens lazily on first read.
        Files.createFile(tempDir.resolve("segments_1"));
        var source = new SolrBackupSource(tempDir, "test", emptySchema(), major);
        assertThat(source.listPartitions("test").size(), equalTo(1));
    }

    @Test
    void rejectsUnsupportedSolrMajorOnRead() throws IOException {
        // Solr 5 is out of scope. Construction succeeds; the failure surfaces when
        // the reader factory is invoked on first read.
        Files.createFile(tempDir.resolve("segments_1"));
        var source = new SolrBackupSource(tempDir, "test", emptySchema(), 5);
        var partition = source.listPartitions("test").get(0);

        var ex = assertThrows(IllegalArgumentException.class,
            () -> source.readDocuments(partition, null));
        assertThat(ex.getMessage().contains("Unsupported Solr major version"), equalTo(true));
    }

    @Test
    void rejectsUuidMappedBackupForPreSolr8() {
        // SolrCloud incremental (UUID-mapped) backups did not exist before Solr 8.9 (SIP-12).
        // If a 6/7 source somehow ends up on the mapped path, fail fast with a clear message.
        var source = new SolrBackupSource(tempDir, "test", emptySchema(), 7);
        var mappedPartition = new SolrShardPartition("test", "shard1", tempDir, java.util.Map.of("segments_1", "uuid"));

        StepVerifier.create(source.readDocuments(mappedPartition, null))
            .expectErrorMatches(t -> t instanceof IllegalStateException
                && t.getMessage().contains("incremental")
                && t.getMessage().contains("Solr 7"))
            .verify();
    }

    /**
     * SOLR-9091 was reported against Solr 6 Cloud, and each major dispatches to a different
     * Lucene reader (6 → IndexReader6, 7 → IndexReader7, 8/9 → IndexReader9), so each fixture is
     * written with the matching Lucene version rather than assuming one stands in for the others.
     */
    @ParameterizedTest(name = "Solr {0}")
    @ValueSource(ints = {6, 7, 8})
    void orphanedSegmentDataWithEmptyCommitFailsLoudly(int solrMajor) throws IOException {
        var indexDir = tempDir.resolve("idx");
        writeIndex(indexDir, 3, solrMajor);
        replaceCommitWithEmptyOne(indexDir, solrMajor);

        var source = new SolrBackupSource(indexDir, "test", emptySchema(), solrMajor);
        var partition = source.listPartitions("test").get(0);

        var ex = assertThrows(SolrBackupReadException.class,
            () -> source.readDocuments(partition, null));
        assertThat(ex, instanceOf(SnapshotReadFailure.class));
        assertThat(ex.getMessage(), org.hamcrest.Matchers.containsString("SOLR-9091"));
        assertThat(ex.getMessage(), org.hamcrest.Matchers.containsString("commitName"));
    }

    @ParameterizedTest(name = "Solr {0}")
    @ValueSource(ints = {6, 7, 8})
    void genuinelyEmptyIndexReadsAsZeroDocuments(int solrMajor) throws IOException {
        // An empty collection is legal. Only orphaned segment data indicates a broken commit,
        // so maxDoc()==0 on its own must not fail.
        var indexDir = tempDir.resolve("idx");
        writeIndex(indexDir, 0, solrMajor);

        var source = new SolrBackupSource(indexDir, "test", emptySchema(), solrMajor);
        var partition = source.listPartitions("test").get(0);

        StepVerifier.create(source.readDocuments(partition, null)).verifyComplete();
    }

    @ParameterizedTest(name = "Solr {0}")
    @ValueSource(ints = {6, 7, 8})
    void healthyIndexReadsAllDocuments(int solrMajor) throws IOException {
        var indexDir = tempDir.resolve("idx");
        writeIndex(indexDir, 3, solrMajor);

        var source = new SolrBackupSource(indexDir, "test", emptySchema(), solrMajor);
        var partition = source.listPartitions("test").get(0);

        StepVerifier.create(source.readDocuments(partition, null))
            .expectNextCount(3)
            .verifyComplete();
    }

    @Test
    void mappedBackupWithOrphanedSegmentDataFailsLoudly() throws IOException {
        var indexDir = tempDir.resolve("index");
        var mapping = writeUuidMappedIndex(indexDir, 3, true);

        var source = new SolrBackupSource(tempDir, "test", emptySchema(), 9);
        var partition = new SolrShardPartition("test", "shard1", indexDir, mapping);

        var ex = assertThrows(SolrBackupReadException.class,
            () -> source.readDocuments(partition, null));
        assertThat(ex, instanceOf(SnapshotReadFailure.class));
        assertThat(ex.getMessage(), org.hamcrest.Matchers.containsString("SOLR-9091"));
    }

    @Test
    void mappedBackupOfGenuinelyEmptyIndexReadsAsZeroDocuments() throws IOException {
        // No _N.* entries in the mapping means there is no orphaned data to complain about.
        var indexDir = tempDir.resolve("index");
        var mapping = writeUuidMappedIndex(indexDir, 0, false);

        var source = new SolrBackupSource(tempDir, "test", emptySchema(), 9);
        var partition = new SolrShardPartition("test", "shard1", indexDir, mapping);

        StepVerifier.create(source.readDocuments(partition, null)).verifyComplete();
    }

    @Test
    void healthyMappedBackupReadsAllDocuments() throws IOException {
        var indexDir = tempDir.resolve("index");
        var mapping = writeUuidMappedIndex(indexDir, 3, false);

        var source = new SolrBackupSource(tempDir, "test", emptySchema(), 9);
        var partition = new SolrShardPartition("test", "shard1", indexDir, mapping);

        StepVerifier.create(source.readDocuments(partition, null))
            .expectNextCount(3)
            .verifyComplete();
    }

    /**
     * Builds the SolrCloud incremental (SIP-12) layout: physical files renamed to UUIDs plus a
     * logical-to-physical mapping. With {@code breakCommit}, the mapped segments_N is an empty
     * commit, so the UUID data files end up orphaned exactly as in the direct-path case.
     */
    private Map<String, String> writeUuidMappedIndex(Path indexDir, int docCount, boolean breakCommit)
            throws IOException {
        var staging = tempDir.resolve("staging");
        writeIndex(staging, docCount, 9);
        if (breakCommit) {
            replaceCommitWithEmptyOne(staging, 9);
        }

        Files.createDirectories(indexDir);
        var mapping = new java.util.LinkedHashMap<String, String>();
        try (var files = Files.list(staging)) {
            for (var file : files.toList()) {
                var logicalName = file.getFileName().toString();
                if (logicalName.equals("write.lock")) {
                    continue;
                }
                var uuid = java.util.UUID.nameUUIDFromBytes(logicalName.getBytes(StandardCharsets.UTF_8)).toString();
                Files.copy(file, indexDir.resolve(uuid));
                mapping.put(logicalName, uuid);
            }
        }
        return mapping;
    }

    /** Writes a real single-segment index with the Lucene version the given Solr major uses. */
    private static void writeIndex(Path indexDir, int docCount, int solrMajor) throws IOException {
        Files.createDirectories(indexDir);
        switch (solrMajor) {
            case 6 -> writeLucene6Index(indexDir, docCount);
            case 7 -> writeLucene7Index(indexDir, docCount);
            default -> writeLucene9Index(indexDir, docCount);
        }
    }

    private static void writeLucene6Index(Path indexDir, int docCount) throws IOException {
        try (var dir = shadow.lucene6.org.apache.lucene.store.FSDirectory.open(indexDir);
             var writer = new shadow.lucene6.org.apache.lucene.index.IndexWriter(dir,
                 new shadow.lucene6.org.apache.lucene.index.IndexWriterConfig(
                     new shadow.lucene6.org.apache.lucene.analysis.core.KeywordAnalyzer()))) {
            for (int i = 0; i < docCount; i++) {
                var doc = new shadow.lucene6.org.apache.lucene.document.Document();
                doc.add(new shadow.lucene6.org.apache.lucene.document.StringField(
                    "id", "doc" + i, shadow.lucene6.org.apache.lucene.document.Field.Store.YES));
                writer.addDocument(doc);
            }
            writer.commit();
        }
    }

    private static void writeLucene7Index(Path indexDir, int docCount) throws IOException {
        try (var dir = shadow.lucene7.org.apache.lucene.store.FSDirectory.open(indexDir);
             var writer = new shadow.lucene7.org.apache.lucene.index.IndexWriter(dir,
                 new shadow.lucene7.org.apache.lucene.index.IndexWriterConfig(
                     new shadow.lucene7.org.apache.lucene.analysis.core.KeywordAnalyzer()))) {
            for (int i = 0; i < docCount; i++) {
                var doc = new shadow.lucene7.org.apache.lucene.document.Document();
                doc.add(new shadow.lucene7.org.apache.lucene.document.StringField(
                    "id", "doc" + i, shadow.lucene7.org.apache.lucene.document.Field.Store.YES));
                writer.addDocument(doc);
            }
            writer.commit();
        }
    }

    private static void writeLucene9Index(Path indexDir, int docCount) throws IOException {
        try (var dir = FSDirectory.open(indexDir);
             var writer = new IndexWriter(dir, new IndexWriterConfig(new KeywordAnalyzer()))) {
            for (int i = 0; i < docCount; i++) {
                var doc = new shadow.lucene9.org.apache.lucene.document.Document();
                doc.add(new StringField("id", "doc" + i, Field.Store.YES));
                writer.addDocument(doc);
            }
            writer.commit();
        }
    }

    /**
     * Reproduces SOLR-9091: overwrite the index's segments_N with a commit that enumerates no
     * segments, leaving the _N.* data files with nothing referencing them.
     */
    private void replaceCommitWithEmptyOne(Path indexDir, int solrMajor) throws IOException {
        var emptyDir = tempDir.resolve("empty-commit");
        writeIndex(emptyDir, 0, solrMajor);

        var emptyCommit = findSegmentsFile(emptyDir);
        var realCommit = findSegmentsFile(indexDir);
        Files.copy(emptyCommit, realCommit, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static Path findSegmentsFile(Path dir) throws IOException {
        try (var files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().startsWith("segments_"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no segments_N in " + dir));
        }
    }

    private static com.fasterxml.jackson.databind.JsonNode emptySchema() {
        var schema = MAPPER.createObjectNode();
        schema.set("fields", MAPPER.createArrayNode());
        return schema;
    }
}
