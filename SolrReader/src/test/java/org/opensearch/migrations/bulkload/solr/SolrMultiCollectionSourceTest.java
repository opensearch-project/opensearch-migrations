package org.opensearch.migrations.bulkload.solr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

/**
 * Unit tests covering the version-aware constructors and the lazy collection/shard
 * preparer behavior of SolrMultiCollectionSource.
 */
class SolrMultiCollectionSourceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void twoArgConstructorListsRegisteredCollections() throws IOException {
        seedSingleShardBackup(tempDir.resolve("collA"));
        seedSingleShardBackup(tempDir.resolve("collB"));
        var schemas = new LinkedHashMap<String, JsonNode>();
        schemas.put("collA", schemaWrapper());
        schemas.put("collB", schemaWrapper());

        try (var source = new SolrMultiCollectionSource(tempDir, schemas, null, null, 8)) {
            var names = source.listCollections();
            assertThat(names.size(), equalTo(2));
            assertThat(names, hasItem("collA"));
            assertThat(names, hasItem("collB"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void threeArgConstructorRunsCollectionPreparerOncePerCollection() throws IOException {
        seedSingleShardBackup(tempDir.resolve("collA"));
        var schemas = new LinkedHashMap<String, JsonNode>();
        schemas.put("collA", schemaWrapper());

        var preparerCalls = new AtomicInteger(0);
        var preparedName = new AtomicReference<String>();

        try (var source = new SolrMultiCollectionSource(tempDir, schemas, c -> {
                preparerCalls.incrementAndGet();
                preparedName.set(c);
            }, null, 8)) {
            // First access triggers the preparer.
            source.listPartitions("collA");
            // Subsequent accesses must NOT call the preparer again.
            source.listPartitions("collA");
            source.readCollectionMetadata("collA");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        assertThat(preparerCalls.get(), equalTo(1));
        assertThat(preparedName.get(), equalTo("collA"));
    }

    @Test
    void fourArgConstructorRunsShardPreparerOncePerShard() throws IOException {
        // Two-shard backup: <collA>/shard1/segments_1, <collA>/shard2/segments_1
        var collA = tempDir.resolve("collA");
        for (var shard : new String[]{"shard1", "shard2"}) {
            var shardDir = collA.resolve(shard);
            Files.createDirectories(shardDir);
            Files.createFile(shardDir.resolve("segments_1"));
        }
        var schemas = Map.<String, JsonNode>of("collA", schemaWrapper());

        var shardCalls = new AtomicInteger(0);
        try (var source = new SolrMultiCollectionSource(tempDir, schemas, null, p -> {
                shardCalls.incrementAndGet();
            }, 8)) {
            var partitions = source.listPartitions("collA");
            assertThat(partitions.size(), equalTo(2));
            // Reading from each partition triggers the shardPreparer once.
            for (var p : partitions) {
                // Subscribe but don't wait — readDocuments returns a Flux. Here we just
                // need the prepare-on-first-access path to fire.
                source.readDocuments(p, 0L).subscribe().dispose();
                // Calling readDocuments again on the same partition must NOT re-prepare.
                source.readDocuments(p, 0L).subscribe().dispose();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        assertThat(shardCalls.get(), equalTo(2));
    }

    @Test
    void fiveArgConstructorAcceptsExplicitSolrMajor() throws IOException {
        // Major version 6 is accepted — exercising the version-threading path.
        seedSingleShardBackup(tempDir.resolve("collA"));
        var schemas = Map.<String, JsonNode>of("collA", schemaWrapper());

        try (var source = new SolrMultiCollectionSource(tempDir, schemas, null, null, 6)) {
            var partitions = source.listPartitions("collA");
            assertThat(partitions.size(), equalTo(1));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void readCollectionMetadataReturnsExpectedShardCount() throws IOException {
        // 3 shards
        var collA = tempDir.resolve("collA");
        for (var shard : new String[]{"shard1", "shard2", "shard3"}) {
            var shardDir = collA.resolve(shard);
            Files.createDirectories(shardDir);
            Files.createFile(shardDir.resolve("segments_1"));
        }
        var schemas = Map.<String, JsonNode>of("collA", schemaWrapper());

        try (var source = new SolrMultiCollectionSource(tempDir, schemas, null, null, 8)) {
            var meta = source.readCollectionMetadata("collA");
            assertThat(meta.partitionCount(), equalTo(3));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void cachedSourceIsReusedAcrossCalls() throws IOException {
        // listPartitions then readCollectionMetadata on the same collection must hit the
        // same internal SolrBackupSource — verified indirectly: the preparer fires once.
        seedSingleShardBackup(tempDir.resolve("collA"));
        var schemas = Map.<String, JsonNode>of("collA", schemaWrapper());

        var preparerCalls = new AtomicInteger(0);
        try (var source = new SolrMultiCollectionSource(tempDir, schemas,
                c -> preparerCalls.incrementAndGet(), null, 8)) {
            source.listPartitions("collA");
            source.readCollectionMetadata("collA");
            source.listPartitions("collA");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        assertThat(preparerCalls.get(), equalTo(1));
    }

    private static void seedSingleShardBackup(Path collDir) throws IOException {
        Files.createDirectories(collDir);
        Files.createFile(collDir.resolve("segments_1"));
    }

    /** Minimal valid schema wrapper accepted by SolrSchemaConverter. */
    private static JsonNode schemaWrapper() {
        var schema = MAPPER.createObjectNode();
        schema.set("fields", MAPPER.createArrayNode());
        var wrapper = MAPPER.createObjectNode();
        wrapper.set("schema", schema);
        return wrapper;
    }
}
