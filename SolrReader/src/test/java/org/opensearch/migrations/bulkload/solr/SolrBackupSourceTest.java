package org.opensearch.migrations.bulkload.solr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.test.StepVerifier;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void fallsBackToSingleShardForEmptyDir() throws IOException {
        // Empty directory — falls back to treating it as single shard
        var source = new SolrBackupSource(tempDir, "test", emptySchema(), 8);
        var partitions = source.listPartitions("test");

        assertThat("Fallback to single shard", partitions.size(), equalTo(1));
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
            () -> source.readDocuments(partition, 0));
        assertThat(ex.getMessage().contains("Unsupported Solr major version"), equalTo(true));
    }

    @Test
    void rejectsUuidMappedBackupForPreSolr8() {
        // SolrCloud incremental (UUID-mapped) backups did not exist before Solr 8.9 (SIP-12).
        // If a 6/7 source somehow ends up on the mapped path, fail fast with a clear message.
        var source = new SolrBackupSource(tempDir, "test", emptySchema(), 7);
        var mappedPartition = new SolrShardPartition("test", "shard1", tempDir, java.util.Map.of("segments_1", "uuid"));

        StepVerifier.create(source.readDocuments(mappedPartition, 0))
            .expectErrorMatches(t -> t instanceof IllegalStateException
                && t.getMessage().contains("incremental")
                && t.getMessage().contains("Solr 7"))
            .verify();
    }

    private static com.fasterxml.jackson.databind.JsonNode emptySchema() {
        var schema = MAPPER.createObjectNode();
        schema.set("fields", MAPPER.createArrayNode());
        return schema;
    }
}
