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
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests covering the version-aware constructors and the IndexMetadata.Factory contract
 * implemented by SolrBackupIndexMetadataFactory.
 */
class SolrBackupIndexMetadataFactoryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void collectionPreparerRunsExplicitly() throws IOException {
        seedSingleShardBackup(tempDir.resolve("collA"));
        var schemas = new LinkedHashMap<String, JsonNode>();
        schemas.put("collA", null); // populated by the preparer

        var preparerCalls = new AtomicInteger(0);
        var preparedCollection = new AtomicReference<String>();
        var factory = new SolrBackupIndexMetadataFactory(tempDir, schemas, c -> {
            preparerCalls.incrementAndGet();
            preparedCollection.set(c);
            schemas.put(c, schemaWithOneField());
        }, 8);

        factory.fromRepo("snap1", "collA");

        assertThat(preparerCalls.get(), equalTo(1));
        assertThat(preparedCollection.get(), equalTo("collA"));
    }

    @Test
    void fourArgConstructorAcceptsExplicitSolrMajor() throws IOException {
        // Major version 6 is accepted (Solr 6 path) even without invoking Lucene readers.
        seedSingleShardBackup(tempDir.resolve("collA"));
        var schemas = Map.<String, JsonNode>of("collA", schemaWithOneField());

        var factory = new SolrBackupIndexMetadataFactory(tempDir, schemas, null, 6);
        var meta = factory.fromRepo("snap1", "collA");
        assertThat(meta.getName(), equalTo("collA"));
    }

    @Test
    void fromRepoBuildsMappingsFromSchema() throws IOException {
        seedSingleShardBackup(tempDir.resolve("collA"));
        var schemas = Map.<String, JsonNode>of("collA", schemaWithOneField());

        var factory = new SolrBackupIndexMetadataFactory(tempDir, schemas, null, 9);
        var meta = factory.fromRepo("snap1", "collA");
        var json = MAPPER.readTree(((SolrIndexMetadata) meta).getRawJson().toString());

        var props = json.path("mappings").path("properties");
        assertThat("schema field landed in mappings", props.has("title"), equalTo(true));
        assertThat(props.path("title").path("type").asText(), equalTo("text"));
        assertThat(json.path("settings").path("index").path("number_of_shards").asText(), equalTo("1"));
        assertThat(json.path("settings").path("index").path("number_of_replicas").asText(), equalTo("1"));
    }

    @Test
    void fromRepoCountsShardsCorrectly() throws IOException {
        // Three shard subdirectories, each with a segments file, should produce shard count 3.
        var collDir = tempDir.resolve("collA");
        for (var shard : new String[]{"shard1", "shard2", "shard3"}) {
            var shardDir = collDir.resolve(shard);
            Files.createDirectories(shardDir);
            Files.createFile(shardDir.resolve("segments_1"));
        }
        var schemas = Map.<String, JsonNode>of("collA", schemaWithOneField());

        var factory = new SolrBackupIndexMetadataFactory(tempDir, schemas, null, 9);
        var meta = factory.fromRepo("snap1", "collA");
        var json = MAPPER.readTree(((SolrIndexMetadata) meta).getRawJson().toString());

        assertThat(json.path("settings").path("index").path("number_of_shards").asText(), equalTo("3"));
    }

    @Test
    void fromRepoWithoutSchemaUsesEmptyMappings() throws IOException {
        // When no schema is registered for the index, fromRepo should fall back to an empty
        // mappings object rather than throwing.
        seedSingleShardBackup(tempDir.resolve("collA"));
        var schemas = new LinkedHashMap<String, JsonNode>();
        // schemas is intentionally empty — collA has no entry.

        var factory = new SolrBackupIndexMetadataFactory(tempDir, schemas, null, 9);
        var meta = factory.fromRepo("snap1", "collA");
        var json = MAPPER.readTree(((SolrIndexMetadata) meta).getRawJson().toString());

        // Empty schema → properties container with no fields inside.
        assertThat(json.path("mappings").path("properties").size(), equalTo(0));
    }

    @Test
    void getRepoDataProviderEnumeratesIndices() {
        var schemas = new LinkedHashMap<String, JsonNode>();
        schemas.put("collA", schemaWithOneField());
        schemas.put("collB", schemaWithOneField());

        var factory = new SolrBackupIndexMetadataFactory(tempDir, schemas, null, 8);
        var indices = factory.getRepoDataProvider().getIndicesInSnapshot("any");

        assertThat(indices.size(), equalTo(2));
        var names = indices.stream().map(idx -> idx.getName()).toList();
        assertThat(names, hasItem("collA"));
        assertThat(names, hasItem("collB"));
    }

    @Test
    void getRepoDataProviderRejectsRepoAccess() {
        var factory = new SolrBackupIndexMetadataFactory(tempDir, Map.of(), null, 8);
        var provider = factory.getRepoDataProvider();
        assertThrows(UnsupportedOperationException.class, provider::getRepo);
    }

    @Test
    void smileFactoryAndFromJsonNodeAreUnsupported() {
        var factory = new SolrBackupIndexMetadataFactory(tempDir, Map.of(), null, 8);
        assertThrows(UnsupportedOperationException.class, factory::getSmileFactory);
        assertThrows(UnsupportedOperationException.class,
            () -> factory.fromJsonNode(MAPPER.createObjectNode(), "id", "name"));
    }

    @Test
    void getIndexFileIdReturnsIndexName() {
        var factory = new SolrBackupIndexMetadataFactory(tempDir, Map.of(), null, 8);
        assertThat(factory.getIndexFileId("snap1", "collA"), equalTo("collA"));
    }

    private static void seedSingleShardBackup(Path collDir) throws IOException {
        Files.createDirectories(collDir);
        Files.createFile(collDir.resolve("segments_1"));
    }

    private static JsonNode schemaWithOneField() {
        var schema = MAPPER.createObjectNode();
        var fields = MAPPER.createArrayNode();
        var title = MAPPER.createObjectNode();
        title.put("name", "title");
        title.put("type", "text_general");
        title.put("stored", true);
        fields.add(title);
        schema.set("fields", fields);

        var fieldTypes = MAPPER.createArrayNode();
        var ft = MAPPER.createObjectNode();
        ft.put("name", "text_general");
        ft.put("class", "solr.TextField");
        fieldTypes.add(ft);
        schema.set("fieldTypes", fieldTypes);

        var wrapper = MAPPER.createObjectNode();
        wrapper.set("schema", schema);
        return wrapper;
    }
}
