package org.opensearch.migrations.bulkload.solr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

class SolrBackupSourceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void discoversSingleShardFlatBackup() throws IOException {
        // Flat backup: segments_N at top level
        Files.createFile(tempDir.resolve("segments_1"));
        Files.createFile(tempDir.resolve("_0.cfs"));

        var source = new SolrBackupSource(tempDir, "test", emptySchema());
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

        var source = new SolrBackupSource(tempDir, "test", emptySchema());
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

        var source = new SolrBackupSource(tempDir, "test", emptySchema());
        var partitions = source.listPartitions("test");

        assertThat("Two SolrCloud shards", partitions.size(), equalTo(2));
    }

    @Test
    void fallsBackToSingleShardForEmptyDir() throws IOException {
        // Empty directory — falls back to treating it as single shard
        var source = new SolrBackupSource(tempDir, "test", emptySchema());
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

        var source = new SolrBackupSource(tempDir, "test", emptySchema());
        var metadata = source.readCollectionMetadata("test");

        assertThat("Partition count matches shards", metadata.partitionCount(), equalTo(3));
    }

    private static com.fasterxml.jackson.databind.JsonNode emptySchema() {
        var schema = MAPPER.createObjectNode();
        schema.set("fields", MAPPER.createArrayNode());
        return schema;
    }
}
