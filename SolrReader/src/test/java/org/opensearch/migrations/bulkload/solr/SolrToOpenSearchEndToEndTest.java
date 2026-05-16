package org.opensearch.migrations.bulkload.solr;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.common.DocumentExceptionAllowlist;
import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.RestClient;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.SearchClusterRequests;
import org.opensearch.migrations.bulkload.pipeline.DocumentMigrationPipeline;
import org.opensearch.migrations.bulkload.pipeline.adapter.OpenSearchDocumentSink;
import org.opensearch.migrations.bulkload.solr.framework.SolrClusterContainer;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests: Solr backup (Lucene) → pipeline → OpenSearch.
 */
@Slf4j
@Tag("isolatedTest")
@Timeout(value = 10, unit = TimeUnit.MINUTES)
public class SolrToOpenSearchEndToEndTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String COLLECTION_NAME = "test_collection";

    @TempDir
    File tempDir;

    static Stream<Arguments> solr8ToOpenSearch() {
        return Stream.of(
            Arguments.of(SolrClusterContainer.SOLR_8, SearchClusterContainer.OS_V2_19_4)
        );
    }

    static Stream<Arguments> solrToOpenSearch() {
        return Stream.of(
            Arguments.of(SolrClusterContainer.SOLR_6, SearchClusterContainer.OS_V2_19_4),
            Arguments.of(SolrClusterContainer.SOLR_7, SearchClusterContainer.OS_V2_19_4),
            Arguments.of(SolrClusterContainer.SOLR_8, SearchClusterContainer.OS_V2_19_4),
            Arguments.of(SolrClusterContainer.SOLR_9, SearchClusterContainer.OS_V2_19_4)
        );
    }

    /** Solr 6 and 7 only — Trie* numeric/date types are pre-Solr-7-Point-defaults legacy. */
    static Stream<Arguments> solr6And7ToOpenSearch() {
        return Stream.of(
            Arguments.of(SolrClusterContainer.SOLR_6, SearchClusterContainer.OS_V2_19_4),
            Arguments.of(SolrClusterContainer.SOLR_7, SearchClusterContainer.OS_V2_19_4)
        );
    }

    @ParameterizedTest(name = "{0} → {1}")
    @MethodSource("solrToOpenSearch")
    void fullMigrationFromBackup(
        SolrClusterContainer.SolrVersion solrVersion,
        SearchClusterContainer.ContainerVersion targetVersion
    ) throws Exception {
        try (
            var solr = new SolrClusterContainer(solrVersion);
            var target = new SearchClusterContainer(targetVersion)
        ) {
            solr.start();
            target.start();

            createSolrCollection(solr, COLLECTION_NAME);
            populateSolrDocuments(solr, COLLECTION_NAME, 10);

            var schema = fetchSolrSchema(solr, COLLECTION_NAME);
            var backupDir = createAndCopyBackup(solr, COLLECTION_NAME);

            var source = new SolrBackupSource(backupDir, COLLECTION_NAME, schema, solrVersion.major());
            var targetClient = createOpenSearchClient(target);
            var sink = new OpenSearchDocumentSink(
                targetClient, null, false, DocumentExceptionAllowlist.empty(), null
            );
            var pipeline = new DocumentMigrationPipeline(source, sink, 100, Long.MAX_VALUE);

            var cursors = pipeline.migrateAll().collectList().block();

            assertThat("Should have progress cursors", cursors.size(), greaterThan(0));
            verifyDocCount(target, COLLECTION_NAME, 10);
        }
    }

    /**
     * Tests migration of diverse Solr field types with various stored/docValues/indexed
     * combinations.
     */
    @ParameterizedTest(name = "field types: {0} → {1}")
    @MethodSource("solr8ToOpenSearch")
    void migratesAllFieldTypesAndStorageCombinations(
        SolrClusterContainer.SolrVersion solrVersion,
        SearchClusterContainer.ContainerVersion targetVersion
    ) throws Exception {
        try (
            var solr = new SolrClusterContainer(solrVersion);
            var target = new SearchClusterContainer(targetVersion)
        ) {
            solr.start();
            target.start();

            createSolrCollection(solr, COLLECTION_NAME);
            addSchemaFields(solr, COLLECTION_NAME);
            indexRichDocument(solr, COLLECTION_NAME);

            var schema = fetchSolrSchema(solr, COLLECTION_NAME);
            var backupDir = createAndCopyBackup(solr, COLLECTION_NAME);

            var source = new SolrBackupSource(backupDir, COLLECTION_NAME, schema, solrVersion.major());
            var targetClient = createOpenSearchClient(target);
            var sink = new OpenSearchDocumentSink(
                targetClient, null, false, DocumentExceptionAllowlist.empty(), null
            );
            var pipeline = new DocumentMigrationPipeline(source, sink, 100, Long.MAX_VALUE);
            pipeline.migrateAll().collectList().block();

            var restClient = createRestClient(target);
            var ctx = DocumentMigrationTestContext.factory().noOtelTracking();
            restClient.get("_refresh", ctx.createUnboundRequestContext());

            // --- Verify mappings ---
            var mappingResp = restClient.get(
                COLLECTION_NAME + "/_mapping", ctx.createUnboundRequestContext()
            );
            var properties = MAPPER.readTree(mappingResp.body)
                .path(COLLECTION_NAME).path("mappings").path("properties");
            log.atInfo().setMessage("OpenSearch mappings: {}").addArgument(properties).log();

            assertThat("id → keyword", properties.path("id").path("type").asText(), equalTo("keyword"));
            assertThat("stored_keyword → keyword", properties.path("stored_keyword").path("type").asText(), equalTo("keyword"));
            assertThat("stored_text → text", properties.path("stored_text").path("type").asText(), equalTo("text"));
            assertThat("stored_int → integer", properties.path("stored_int").path("type").asText(), equalTo("integer"));
            assertThat("stored_long → long", properties.path("stored_long").path("type").asText(), equalTo("long"));
            assertThat("stored_float → float", properties.path("stored_float").path("type").asText(), equalTo("float"));
            assertThat("stored_double → double", properties.path("stored_double").path("type").asText(), equalTo("double"));
            assertThat("stored_date → date", properties.path("stored_date").path("type").asText(), equalTo("date"));
            assertThat("stored_bool → boolean", properties.path("stored_bool").path("type").asText(), equalTo("boolean"));
            assertThat("stored_binary → binary", properties.path("stored_binary").path("type").asText(), equalTo("binary"));
            assertThat("multi_string → keyword", properties.path("multi_string").path("type").asText(), equalTo("keyword"));
            assertThat("unstored_keyword → keyword", properties.path("unstored_keyword").path("type").asText(), equalTo("keyword"));
            assertThat("unstored_int → integer", properties.path("unstored_int").path("type").asText(), equalTo("integer"));

            // --- Verify document field values ---
            var searchResp = restClient.get(
                COLLECTION_NAME + "/_search?q=id:doc1&size=1", ctx.createUnboundRequestContext()
            );
            var hits = MAPPER.readTree(searchResp.body).path("hits").path("hits");
            assertThat("Should find doc1", hits.size(), equalTo(1));

            var doc = hits.get(0).path("_source");
            log.atInfo().setMessage("Migrated doc: {}").addArgument(doc).log();

            assertThat("stored_keyword value", doc.path("stored_keyword").asText(), equalTo("hello"));
            assertThat("nodv_keyword value", doc.path("nodv_keyword").asText(), equalTo("no-docvalues"));
            assertThat("stored_text value", doc.path("stored_text").asText(), equalTo("full text search"));
            assertThat("stored_int value", doc.path("stored_int").asInt(), equalTo(42));
            assertThat("stored_long value", doc.path("stored_long").asLong(), equalTo(1234567890L));
            assertThat("stored_float value", (double) doc.path("stored_float").floatValue(), closeTo(3.14, 0.01));
            assertThat("stored_double value", doc.path("stored_double").doubleValue(), closeTo(2.718281828, 0.0001));
            assertThat("stored_date value", doc.path("stored_date").asLong(), equalTo(1705314600000L));
            assertThat("stored_bool value", doc.path("stored_bool").asBoolean(), equalTo(true));

            assertTrue(doc.path("multi_string").isArray(), "multi_string should be an array");
            assertThat("multi_string size", doc.path("multi_string").size(), equalTo(3));
            var multiValues = new HashSet<String>();
            doc.path("multi_string").forEach(v -> multiValues.add(v.asText()));
            assertTrue(multiValues.contains("alpha"), "multi_string should contain alpha");
            assertTrue(multiValues.contains("beta"), "multi_string should contain beta");
            assertTrue(multiValues.contains("gamma"), "multi_string should contain gamma");
        }
    }

    /**
     * E2E: Solr fields whose names contain '.' migrate with their declared types,
     * {@code _source} preserves the original dotted keys verbatim, and queries
     * against the original dotted path resolve.
     *
     * <p>SOURCE — Solr 8 collection with five explicitly declared dotted-name fields,
     * one document.
     * <pre>{@code
     *   schema:
     *     category.name      string  (→ Solr "string"  → OS keyword)
     *     category.id        pint    (→ Solr "pint"    → OS integer)
     *     metric.cpu.percent pfloat  (→ Solr "pfloat"  → OS float)
     *     event.created      pdate   (→ Solr "pdate"   → OS date)
     *     is.active          boolean (→ Solr "boolean" → OS boolean)
     *   doc1: {category.name:"books", category.id:7, metric.cpu.percent:42.5,
     *          event.created:"2024-01-15T10:30:00Z", is.active:true}
     * }</pre>
     *
     * <p>TARGET — OpenSearch 2.19 index "test_collection" with the dotted keys
     * round-tripped verbatim in {@code _source}, the declared types reflected in
     * {@code _field_caps}, and queries on the dotted path returning doc1.
     */
    @ParameterizedTest(name = "dotted field names: {0} → {1}")
    @MethodSource("solr8ToOpenSearch")
    void migratesFieldsWithDotsInName(
        SolrClusterContainer.SolrVersion solrVersion,
        SearchClusterContainer.ContainerVersion targetVersion
    ) throws Exception {
        try (
            var solr = new SolrClusterContainer(solrVersion);
            var target = new SearchClusterContainer(targetVersion)
        ) {
            solr.start();
            target.start();

            // ---- SOURCE: Solr collection with explicit dotted-name fields + 1 doc ----
            createSolrCollection(solr, COLLECTION_NAME);
            addSolrSchemaFields(solr, COLLECTION_NAME, "["
                + "{\"name\":\"category.name\",       \"type\":\"string\",  \"stored\":true, \"indexed\":true, \"docValues\":true},"
                + "{\"name\":\"category.id\",         \"type\":\"pint\",    \"stored\":true, \"indexed\":true, \"docValues\":true},"
                + "{\"name\":\"metric.cpu.percent\",  \"type\":\"pfloat\",  \"stored\":true, \"indexed\":true, \"docValues\":true},"
                + "{\"name\":\"event.created\",       \"type\":\"pdate\",   \"stored\":true, \"indexed\":true, \"docValues\":true},"
                + "{\"name\":\"is.active\",           \"type\":\"boolean\", \"stored\":true, \"docValues\":true}"
                + "]");
            indexSolrDocs(solr, COLLECTION_NAME, "[{"
                + "\"id\":\"doc1\","
                + "\"category.name\":\"books\","
                + "\"category.id\":7,"
                + "\"metric.cpu.percent\":42.5,"
                + "\"event.created\":\"2024-01-15T10:30:00Z\","
                + "\"is.active\":true"
                + "}]");

            // ---- MIGRATE: backup → pipeline → OpenSearch ----
            runPipelineMigration(solr, target, COLLECTION_NAME, solrVersion);

            // ---- TARGET: assert metadata (declared types) and document content ----
            var restClient = createRestClient(target);
            var ctx = DocumentMigrationTestContext.factory().noOtelTracking();
            restClient.get("_refresh", ctx.createUnboundRequestContext());

            // Metadata: each declared dotted field is reachable by its full dotted path
            // and reports the OpenSearch type that the converter should derive from Solr.
            assertTargetFieldType(restClient, ctx, COLLECTION_NAME, "category.name",       "keyword");
            assertTargetFieldType(restClient, ctx, COLLECTION_NAME, "category.id",         "integer");
            assertTargetFieldType(restClient, ctx, COLLECTION_NAME, "metric.cpu.percent",  "float");
            assertTargetFieldType(restClient, ctx, COLLECTION_NAME, "event.created",       "date");
            assertTargetFieldType(restClient, ctx, COLLECTION_NAME, "is.active",           "boolean");

            // Document content: _source keeps every dotted key verbatim, with the value as written.
            var src = fetchTargetSource(restClient, ctx, COLLECTION_NAME, "doc1");
            assertThat("_source.category.name",
                src.path("category.name").asText(),                    equalTo("books"));
            assertThat("_source.category.id",
                src.path("category.id").asInt(),                       equalTo(7));
            assertThat("_source.metric.cpu.percent",
                (double) src.path("metric.cpu.percent").floatValue(),  closeTo(42.5, 0.01));
            assertThat("_source.is.active",
                src.path("is.active").asBoolean(),                     equalTo(true));

            // Queries by the original dotted path resolve back to doc1 — including a
            // three-segment field name. This is what Solr customers actually issue.
            assertQueryReturnsId(restClient, ctx, COLLECTION_NAME, "category.name:books",        "doc1");
            assertQueryReturnsId(restClient, ctx, COLLECTION_NAME, "category.id:7",              "doc1");
            assertQueryReturnsId(restClient, ctx, COLLECTION_NAME, "metric.cpu.percent:42.5",    "doc1");
        }
    }

    /**
     * E2E regression: a dotted-name field whose top-level segment also matches a
     * Solr {@code dynamicField} pattern. Before the fix, the converter emitted
     * dynamic templates with plain {@code match}, which fired on the synthesized
     * parent object on write and failed bulk indexing with
     * {@code mapper_parsing_exception} — silently dropping the doc.
     *
     * <p>SOURCE — Solr 8 collection (default schema's dynamic fields) with one doc
     * that exercises each of the six default dynamic-field patterns using a dotted
     * leaf so the parent segment also prefix-matches the pattern.
     * <pre>{@code
     *   dynamic fields (default Solr 8 schema):
     *     attr_*  text_general,  *_s string,  *_i pint,  *_b boolean,  *_f pfloat,  *_dt pdate
     *   dyn-dot-1: {
     *     attr_thing.withdot:"vip",       // attr_*  + dotted leaf
     *     label.tag_s:"alpha",            // *_s     + dotted leaf
     *     counter.value_i:17,             // *_i     + dotted leaf
     *     flag.is.set_b:true,             // *_b     + dotted leaf (3 segments)
     *     price.unit_f:9.99,              // *_f     + dotted leaf
     *     event.created_dt:"2024-06-15T12:00:00Z"  // *_dt + dotted leaf
     *   }
     * }</pre>
     *
     * <p>TARGET — OpenSearch 2.19 index "test_collection" with the doc successfully
     * indexed (no bulk failure), each dotted leaf typed by its dynamic template,
     * and dotted-path queries returning the doc.
     */
    @ParameterizedTest(name = "dynamic-field + dotted name: {0} → {1}")
    @MethodSource("solr8ToOpenSearch")
    void migratesDynamicFieldsWithDotsInLeafName(
        SolrClusterContainer.SolrVersion solrVersion,
        SearchClusterContainer.ContainerVersion targetVersion
    ) throws Exception {
        try (
            var solr = new SolrClusterContainer(solrVersion);
            var target = new SearchClusterContainer(targetVersion)
        ) {
            solr.start();
            target.start();

            // ---- SOURCE: Solr collection (default dynamicFields) + 1 doc with dotted leaves ----
            createSolrCollection(solr, COLLECTION_NAME);
            indexSolrDocs(solr, COLLECTION_NAME, "[{"
                + "\"id\":\"dyn-dot-1\","
                + "\"attr_thing.withdot\":\"vip\","
                + "\"label.tag_s\":\"alpha\","
                + "\"counter.value_i\":17,"
                + "\"flag.is.set_b\":true,"
                + "\"price.unit_f\":9.99,"
                + "\"event.created_dt\":\"2024-06-15T12:00:00Z\""
                + "}]");

            // ---- MIGRATE: backup → pipeline → OpenSearch ----
            runPipelineMigration(solr, target, COLLECTION_NAME, solrVersion);

            // ---- TARGET: assert metadata (per-template types) and document content ----
            var restClient = createRestClient(target);
            var ctx = DocumentMigrationTestContext.factory().noOtelTracking();
            restClient.get("_refresh", ctx.createUnboundRequestContext());

            // The doc must exist — pre-fix, bulk would have failed with mapper_parsing_exception.
            var src = fetchTargetSource(restClient, ctx, COLLECTION_NAME, "dyn-dot-1");
            assertThat("_source.attr_thing.withdot", src.path("attr_thing.withdot").asText(),         equalTo("vip"));
            assertThat("_source.label.tag_s",        src.path("label.tag_s").asText(),                equalTo("alpha"));
            assertThat("_source.counter.value_i",    src.path("counter.value_i").asInt(),             equalTo(17));
            assertThat("_source.flag.is.set_b",      src.path("flag.is.set_b").asBoolean(),           equalTo(true));
            assertThat("_source.price.unit_f",       (double) src.path("price.unit_f").floatValue(),  closeTo(9.99, 0.01));

            // Each dotted leaf got the type its matching dynamic template prescribes —
            // proves path_match + match_mapping_type targeted only the leaf, not the parent.
            assertTargetFieldType(restClient, ctx, COLLECTION_NAME, "attr_thing.withdot",  "text");      // attr_* template
            assertTargetFieldType(restClient, ctx, COLLECTION_NAME, "label.tag_s",         "keyword");   // *_s    template
            assertTargetFieldType(restClient, ctx, COLLECTION_NAME, "counter.value_i",     "integer");   // *_i    template
            assertTargetFieldType(restClient, ctx, COLLECTION_NAME, "flag.is.set_b",       "boolean");   // *_b    template
            assertTargetFieldType(restClient, ctx, COLLECTION_NAME, "price.unit_f",        "float");     // *_f    template
            assertTargetFieldType(restClient, ctx, COLLECTION_NAME, "event.created_dt",    "date");      // *_dt   template

            // Query by the original dotted path resolves to the doc.
            assertQueryReturnsId(restClient, ctx, COLLECTION_NAME, "counter.value_i:17", "dyn-dot-1");
        }
    }

    /**
     * E2E: Multi-shard backup discovery and parallel reading.
     * Creates a simulated multi-shard backup directory structure and verifies
     * all shards are discovered and documents from each shard are migrated.
     */
    @ParameterizedTest(name = "multi-shard: {0} → {1}")
    @MethodSource("solrToOpenSearch")
    void multiShardBackupMigration(
        SolrClusterContainer.SolrVersion solrVersion,
        SearchClusterContainer.ContainerVersion targetVersion
    ) throws Exception {
        try (
            var solr = new SolrClusterContainer(solrVersion);
            var target = new SearchClusterContainer(targetVersion)
        ) {
            solr.start();
            target.start();

            // Create two separate cores to simulate two shards
            var core1 = "shard1_core";
            var core2 = "shard2_core";
            createSolrCollection(solr, core1);
            createSolrCollection(solr, core2);
            populateSolrDocuments(solr, core1, 5, "s1_");
            populateSolrDocuments(solr, core2, 7, "s2_");

            // Create backups of each core
            var backup1 = createAndCopyBackup(solr, core1, "backup1");
            var backup2 = createAndCopyBackup(solr, core2, "backup2");

            // Assemble a multi-shard backup directory
            var multiShardDir = tempDir.toPath().resolve("multi_shard_backup");
            Files.createDirectories(multiShardDir);
            var shard1Dir = multiShardDir.resolve("shard1");
            var shard2Dir = multiShardDir.resolve("shard2");
            copyDirectory(backup1, shard1Dir);
            copyDirectory(backup2, shard2Dir);

            var schema = fetchSolrSchema(solr, core1);
            var source = new SolrBackupSource(multiShardDir, COLLECTION_NAME, schema, solrVersion.major());

            // Verify shard discovery
            var partitions = source.listPartitions(COLLECTION_NAME);
            assertThat("Should discover 2 shards", partitions.size(), equalTo(2));

            // Migrate all shards with parallel partition processing
            var targetClient = createOpenSearchClient(target);
            var sink = new OpenSearchDocumentSink(
                targetClient, null, false, DocumentExceptionAllowlist.empty(), null
            );
            var pipeline = new DocumentMigrationPipeline(source, sink, 100, Long.MAX_VALUE, 2, 10);
            var cursors = pipeline.migrateAll().collectList().block();

            assertThat("Should have cursors from both shards", cursors.size(), greaterThan(1));
            verifyDocCount(target, COLLECTION_NAME, 12); // 5 + 7
        }
    }

    /**
     * E2E: Dynamic fields and copyFields are converted to OpenSearch mappings.
     * Uses Solr's default dynamic field patterns (*_s, *_i, *_dt, etc.) and
     * verifies documents using dynamic field names are indexed correctly.
     */
    @ParameterizedTest(name = "dynamic fields: {0} → {1}")
    @MethodSource("solrToOpenSearch")
    void dynamicFieldsAndCopyFieldsMigration(
        SolrClusterContainer.SolrVersion solrVersion,
        SearchClusterContainer.ContainerVersion targetVersion
    ) throws Exception {
        try (
            var solr = new SolrClusterContainer(solrVersion);
            var target = new SearchClusterContainer(targetVersion)
        ) {
            solr.start();
            target.start();

            createSolrCollection(solr, COLLECTION_NAME);

            // Add a copyField: title → text_all
            solr.execInContainer("curl", "-s", "-H", "Content-Type: application/json",
                "http://localhost:8983/solr/" + COLLECTION_NAME + "/schema",
                "-d", "{\"add-field\":{\"name\":\"title\",\"type\":\"text_general\",\"stored\":true},"
                    + "\"add-copy-field\":{\"source\":\"title\",\"dest\":\"_text_\"}}");

            // Index docs using dynamic field names (Solr default schema has *_s, *_i, *_dt, etc.)
            var doc = "[{"
                + "\"id\":\"dyn1\","
                + "\"title\":\"Dynamic Test\","
                + "\"category_s\":\"electronics\","
                + "\"count_i\":42,"
                + "\"price_f\":19.99,"
                + "\"created_dt\":\"2024-06-15T12:00:00Z\","
                + "\"active_b\":true"
                + "}]";
            solr.execInContainer("curl", "-s", "-H", "Content-Type: application/json",
                "http://localhost:8983/solr/" + COLLECTION_NAME + "/update?commit=true",
                "-d", doc);

            var schema = fetchSolrSchema(solr, COLLECTION_NAME);
            var backupDir = createAndCopyBackup(solr, COLLECTION_NAME);

            var source = new SolrBackupSource(backupDir, COLLECTION_NAME, schema, solrVersion.major());
            var targetClient = createOpenSearchClient(target);
            var sink = new OpenSearchDocumentSink(
                targetClient, null, false, DocumentExceptionAllowlist.empty(), null
            );
            var pipeline = new DocumentMigrationPipeline(source, sink, 100, Long.MAX_VALUE);
            pipeline.migrateAll().collectList().block();

            var restClient = createRestClient(target);
            var ctx = DocumentMigrationTestContext.factory().noOtelTracking();
            restClient.get("_refresh", ctx.createUnboundRequestContext());

            // Verify the document was migrated with dynamic field values
            var searchResp = restClient.get(
                COLLECTION_NAME + "/_search?q=id:dyn1&size=1", ctx.createUnboundRequestContext()
            );
            var hits = MAPPER.readTree(searchResp.body).path("hits").path("hits");
            assertThat("Should find dyn1", hits.size(), equalTo(1));

            var migrated = hits.get(0).path("_source");
            assertThat("category_s value", migrated.path("category_s").asText(), equalTo("electronics"));
            assertThat("count_i value", migrated.path("count_i").asInt(), equalTo(42));
            assertThat("active_b value", migrated.path("active_b").asBoolean(), equalTo(true));
            assertThat("title value", migrated.path("title").asText(), equalTo("Dynamic Test"));

            // Verify mappings include dynamic_templates from schema
            var mappingResp = restClient.get(
                COLLECTION_NAME + "/_mapping", ctx.createUnboundRequestContext()
            );
            var mappingRoot = MAPPER.readTree(mappingResp.body).path(COLLECTION_NAME).path("mappings");
            var properties = mappingRoot.path("properties");
            // The explicit 'title' field should be in properties
            assertThat("title mapped", properties.has("title"), equalTo(true));

            // AC12: Solr's dynamic field patterns (*_s, *_i, *_dt, etc.) must produce
            // OpenSearch dynamic_templates so further indexing infers types from field-name suffix.
            var dynamicTemplates = mappingRoot.path("dynamic_templates");
            assertTrue(dynamicTemplates.isArray(),
                "dynamic_templates must be an array in OS mapping");
            assertThat("dynamic_templates must be non-empty (Solr default schema has *_s, *_i, *_dt, ...)",
                dynamicTemplates.size(), greaterThan(0));
            // Each entry is a single-key object:
            //   { "<name>": { "path_match": "...", "match_mapping_type": "...", "mapping": { "type": "..." } } }
            // path_match + match_mapping_type scopes the template to leaves only — see
            // SolrSchemaConverter.buildDynamicTemplate for why plain `match` is unsafe.
            var foundStringSuffix = false;
            for (var template : dynamicTemplates) {
                var entry = template.fields().next();
                var spec = entry.getValue();
                assertTrue(spec.has("path_match"),
                    "Dynamic template '" + entry.getKey() + "' must have a path_match clause");
                assertTrue(spec.path("mapping").has("type"),
                    "Dynamic template '" + entry.getKey() + "' must specify a mapping type");
                if (spec.path("path_match").asText().equals("*_s")) {
                    foundStringSuffix = true;
                }
            }
            assertTrue(foundStringSuffix,
                "Expected a dynamic_template with path_match='*_s' (Solr default string suffix)");
        }
    }

    /**
     * E2E: copyField destinations resolve their type from dynamic field patterns, not hardcoded text.
     * Simulates the common Solr pattern: text_general field + copyField to *_str (type=strings)
     * for faceting. Verifies the *_str fields get "keyword" mapping in OpenSearch, not "text".
     */
    @ParameterizedTest(name = "copyField type resolution: {0} → {1}")
    @MethodSource("solr8ToOpenSearch")
    void copyFieldDestinationsResolveTypeFromDynamicFields(
        SolrClusterContainer.SolrVersion solrVersion,
        SearchClusterContainer.ContainerVersion targetVersion
    ) throws Exception {
        try (
            var solr = new SolrClusterContainer(solrVersion);
            var target = new SearchClusterContainer(targetVersion)
        ) {
            solr.start();
            target.start();

            createSolrCollection(solr, COLLECTION_NAME);

            // Add fields, a dynamic field pattern, and copyField directives
            // This mirrors a real-world Solr pattern: text_general fields with *_str for faceting
            solr.execInContainer("curl", "-s", "-H", "Content-Type: application/json",
                "http://localhost:8983/solr/" + COLLECTION_NAME + "/schema",
                "-d", "{\"add-field\":["
                    + "{\"name\":\"brand\",\"type\":\"text_general\",\"stored\":true},"
                    + "{\"name\":\"category\",\"type\":\"text_general\",\"stored\":true}"
                    + "],"
                    + "\"add-dynamic-field\":["
                    + "{\"name\":\"*_str\",\"type\":\"strings\",\"stored\":false,\"docValues\":true,\"indexed\":false}"
                    + "],"
                    + "\"add-copy-field\":["
                    + "{\"source\":\"brand\",\"dest\":\"brand_str\",\"maxChars\":256},"
                    + "{\"source\":\"category\",\"dest\":\"category_str\",\"maxChars\":256}"
                    + "]}");

            // Index a document
            solr.execInContainer("curl", "-s", "-H", "Content-Type: application/json",
                "http://localhost:8983/solr/" + COLLECTION_NAME + "/update?commit=true",
                "-d", "[{\"id\":\"cf1\",\"brand\":\"Acme Corp\",\"category\":\"Electronics\"}]");

            var schema = fetchSolrSchema(solr, COLLECTION_NAME);
            var backupDir = createAndCopyBackup(solr, COLLECTION_NAME);

            var source = new SolrBackupSource(backupDir, COLLECTION_NAME, schema, solrVersion.major());
            var targetClient = createOpenSearchClient(target);
            var sink = new OpenSearchDocumentSink(
                targetClient, null, false, DocumentExceptionAllowlist.empty(), null
            );
            var pipeline = new DocumentMigrationPipeline(source, sink, 100, Long.MAX_VALUE);
            pipeline.migrateAll().collectList().block();

            var restClient = createRestClient(target);
            var ctx = DocumentMigrationTestContext.factory().noOtelTracking();
            restClient.get("_refresh", ctx.createUnboundRequestContext());

            // --- Verify mappings: *_str fields should be "keyword", not "text" ---
            var mappingResp = restClient.get(
                COLLECTION_NAME + "/_mapping", ctx.createUnboundRequestContext()
            );
            var properties = MAPPER.readTree(mappingResp.body)
                .path(COLLECTION_NAME).path("mappings").path("properties");
            log.atInfo().setMessage("CopyField mapping test — properties: {}").addArgument(properties).log();

            assertThat("brand → text", properties.path("brand").path("type").asText(), equalTo("text"));
            assertThat("category → text", properties.path("category").path("type").asText(), equalTo("text"));
            assertThat("brand_str → keyword (from *_str dynamic pattern)",
                properties.path("brand_str").path("type").asText(), equalTo("keyword"));
            assertThat("category_str → keyword (from *_str dynamic pattern)",
                properties.path("category_str").path("type").asText(), equalTo("keyword"));

            // --- Verify the source document fields migrated ---
            var searchResp = restClient.get(
                COLLECTION_NAME + "/_search?q=id:cf1&size=1", ctx.createUnboundRequestContext()
            );
            var hits = MAPPER.readTree(searchResp.body).path("hits").path("hits");
            assertThat("Should find cf1", hits.size(), equalTo(1));

            var doc = hits.get(0).path("_source");
            assertThat("brand value", doc.path("brand").asText(), equalTo("Acme Corp"));
            assertThat("category value", doc.path("category").asText(), equalTo("Electronics"));
        }
    }

    /**
     * E2E: Date fields get proper format mapping (strict_date_optional_time||epoch_millis)
     * so OpenSearch can accept both ISO 8601 strings and epoch millis from Lucene.
     */
    @ParameterizedTest(name = "date format: {0} → {1}")
    @MethodSource("solr8ToOpenSearch")
    void dateFieldsGetProperFormatMapping(
        SolrClusterContainer.SolrVersion solrVersion,
        SearchClusterContainer.ContainerVersion targetVersion
    ) throws Exception {
        try (
            var solr = new SolrClusterContainer(solrVersion);
            var target = new SearchClusterContainer(targetVersion)
        ) {
            solr.start();
            target.start();

            createSolrCollection(solr, COLLECTION_NAME);
            solr.execInContainer("curl", "-s", "-H", "Content-Type: application/json",
                "http://localhost:8983/solr/" + COLLECTION_NAME + "/schema",
                "-d", "{\"add-field\":["
                    + "{\"name\":\"created\",\"type\":\"pdate\",\"stored\":true,\"docValues\":true},"
                    + "{\"name\":\"updated\",\"type\":\"pdate\",\"stored\":true,\"docValues\":true}"
                    + "]}");

            var doc = "[{\"id\":\"date1\",\"created\":\"2024-01-15T10:30:00Z\",\"updated\":\"2024-06-20T15:45:00Z\"}]";
            solr.execInContainer("curl", "-s", "-H", "Content-Type: application/json",
                "http://localhost:8983/solr/" + COLLECTION_NAME + "/update?commit=true",
                "-d", doc);

            var schema = fetchSolrSchema(solr, COLLECTION_NAME);
            var backupDir = createAndCopyBackup(solr, COLLECTION_NAME);

            var source = new SolrBackupSource(backupDir, COLLECTION_NAME, schema, solrVersion.major());
            var targetClient = createOpenSearchClient(target);
            var sink = new OpenSearchDocumentSink(
                targetClient, null, false, DocumentExceptionAllowlist.empty(), null
            );
            new DocumentMigrationPipeline(source, sink, 100, Long.MAX_VALUE)
                .migrateAll().collectList().block();

            var restClient = createRestClient(target);
            var ctx = DocumentMigrationTestContext.factory().noOtelTracking();
            restClient.get("_refresh", ctx.createUnboundRequestContext());

            // Verify date format in mappings
            var mappingResp = restClient.get(
                COLLECTION_NAME + "/_mapping", ctx.createUnboundRequestContext()
            );
            var properties = MAPPER.readTree(mappingResp.body)
                .path(COLLECTION_NAME).path("mappings").path("properties");

            assertThat("created type", properties.path("created").path("type").asText(), equalTo("date"));
            assertThat("updated type", properties.path("updated").path("type").asText(), equalTo("date"));

            // Verify the document was indexed (dates stored as epoch millis should work)
            verifyDocCount(target, COLLECTION_NAME, 1);
        }
    }

    /**
     * E2E: Standalone Solr backup via replication API.
     * Verifies SolrStandaloneBackupCreator can trigger and poll a backup.
     */
    @ParameterizedTest(name = "standalone backup: {0} → {1}")
    @MethodSource("solr8ToOpenSearch")
    void standaloneBackupViReplicationApi(
        SolrClusterContainer.SolrVersion solrVersion,
        SearchClusterContainer.ContainerVersion targetVersion
    ) throws Exception {
        try (
            var solr = new SolrClusterContainer(solrVersion);
            var target = new SearchClusterContainer(targetVersion)
        ) {
            solr.start();
            target.start();

            createSolrCollection(solr, COLLECTION_NAME);
            populateSolrDocuments(solr, COLLECTION_NAME, 8);

            // Use SolrStandaloneBackupCreator to trigger backup via replication API
            var solrContext = new ConnectionContext.SourceArgs() {{ host = solr.getSolrUrl(); insecure = true; }}.toConnectionContext();
            var creator = new SolrStandaloneBackupCreator(
                solr.getSolrUrl(), "test_backup", "/var/solr/data",
                List.of(COLLECTION_NAME), solrContext
            );
            creator.createBackup();

            // Poll until backup completes
            int maxWait = 30;
            while (!creator.isBackupFinished() && maxWait-- > 0) {
                Thread.sleep(1000);
            }
            assertTrue(creator.isBackupFinished(), "Backup should complete within 30s");

            // Copy backup files and migrate
            var localBackupDir = tempDir.toPath().resolve("standalone_backup");
            Files.createDirectories(localBackupDir);
            var snapshotDir = "/var/solr/data/snapshot.test_backup";
            var listResult = solr.execInContainer("ls", snapshotDir);
            for (var fileName : listResult.getStdout().trim().split("\n")) {
                if (fileName.isEmpty()) continue;
                solr.copyFileFromContainer(
                    snapshotDir + "/" + fileName,
                    localBackupDir.resolve(fileName).toString()
                );
            }

            var schema = fetchSolrSchema(solr, COLLECTION_NAME);
            var source = new SolrBackupSource(localBackupDir, COLLECTION_NAME, schema, solrVersion.major());
            var targetClient = createOpenSearchClient(target);
            var sink = new OpenSearchDocumentSink(
                targetClient, null, false, DocumentExceptionAllowlist.empty(), null
            );
            new DocumentMigrationPipeline(source, sink, 100, Long.MAX_VALUE)
                .migrateAll().collectList().block();

            verifyDocCount(target, COLLECTION_NAME, 8);
        }
    }

    /**
     * E2E: SolrCloud backup with multiple shards.
     * Creates a SolrCloud collection with 2 shards, backs up via Collections API,
     * and verifies the backup structure is correctly read with 2 partitions.
     * Migrates all shards and verifies total doc count.
     */
    @ParameterizedTest(name = "solrcloud multi-shard: {0} → {1}")
    @MethodSource("solrToOpenSearch")
    void solrCloudMultiShardBackupMigration(
        SolrClusterContainer.SolrVersion solrVersion,
        SearchClusterContainer.ContainerVersion targetVersion
    ) throws Exception {
        try (
            var solr = SolrClusterContainer.cloud(solrVersion);
            var target = new SearchClusterContainer(targetVersion)
        ) {
            solr.start();
            target.start();

            var collection = "cloud_test";
            var numShards = 2;

            // Solr 6 SolrCloud does NOT auto-upload a configset to ZK; CREATE will fail with
            // "No config set found" unless we upconfig first. Solr 7+ auto-uploads _default.
            if (solrVersion.major() == 6) {
                var up = solr.execInContainer(
                    "/opt/solr/bin/solr", "zk", "upconfig",
                    "-n", "_default",
                    "-d", "/opt/solr/server/solr/configsets/data_driven_schema_configs",
                    "-z", "localhost:9983"
                );
                log.atInfo().setMessage("Solr 6 upconfig: exit={}, out={}, err={}")
                    .addArgument(up.getExitCode())
                    .addArgument(up.getStdout())
                    .addArgument(up.getStderr()).log();
                if (up.getExitCode() != 0) {
                    throw new RuntimeException("Solr 6 upconfig failed: " + up.getStderr());
                }
            }

            // Create SolrCloud collection with 2 shards. maxShardsPerNode was removed in Solr 9.
            // Solr 6 needs explicit collection.configName since it doesn't auto-upload _default.
            var createResult = solr.execInContainer("curl", "-s",
                "http://localhost:8983/solr/admin/collections?action=CREATE"
                    + "&name=" + collection
                    + "&numShards=" + numShards
                    + "&replicationFactor=1"
                    + (solrVersion.major() < 9 ? "&maxShardsPerNode=" + numShards : "")
                    + (solrVersion.major() == 6 ? "&collection.configName=_default" : "")
                    + "&wt=json");
            log.atInfo().setMessage("Create collection response: {}").addArgument(createResult.getStdout()).log();

            // Wait for the collection to be active. Solr 6's CREATE returns before the
            // collection is ready to serve queries, so a subsequent BACKUP can race and fail
            // with "Collection 'X' does not exist".
            waitForCollectionActive(solr, collection, numShards, 60);

            // Index 20 documents (distributed across shards by Solr's hash routing)
            populateSolrDocuments(solr, collection, 20);

            // Probe for an actually-writable Solr data directory. The SOLR_HOME env var is
            // set to /var/solr/data in newer images but the directory doesn't always exist
            // (Solr 7 docker uses /opt/solr/server/solr). Solr 9's solr.allowPaths also
            // restricts BACKUP locations to SOLR_HOME and below by default, so picking a
            // dir under the actual Solr home is necessary for cross-version compatibility.
            var probe = solr.execInContainer("sh", "-c",
                "for d in /var/solr/data /opt/solr/server/solr; do "
                + "  if [ -d \"$d\" ] && [ -w \"$d\" ]; then echo \"$d\"; break; fi; "
                + "done");
            var solrDataDir = probe.getStdout().trim();
            if (solrDataDir.isEmpty()) {
                throw new RuntimeException("No writable Solr data directory found in container");
            }
            log.atInfo().setMessage("Solr {} writable data dir: {}").addArgument(solrVersion).addArgument(solrDataDir).log();
            var backupLocation = solrDataDir + "/backups";
            var mkdirResult = solr.execInContainer("mkdir", "-p", backupLocation);
            if (mkdirResult.getExitCode() != 0) {
                throw new RuntimeException("Failed to mkdir " + backupLocation + ": " + mkdirResult.getStderr());
            }
            var lsCheck = solr.execInContainer("sh", "-c", "ls -ld " + backupLocation + " 2>&1");
            log.atInfo().setMessage("Backup dir status: {}").addArgument(lsCheck.getStdout().trim()).log();

            var backupResult = solr.execInContainer("curl", "-s",
                "http://localhost:8983/solr/admin/collections?action=BACKUP"
                    + "&name=cloud_backup"
                    + "&collection=" + collection
                    + "&location=" + backupLocation
                    + "&wt=json");
            log.atInfo().setMessage("Backup response: {}").addArgument(backupResult.getStdout()).log();
            if (backupResult.getStdout().contains("\"status\":500") || backupResult.getStdout().contains("\"status\":400")) {
                throw new IllegalStateException("BACKUP failed for " + solrVersion + ": " + backupResult.getStdout());
            }

            // Copy the entire backup tree from the container. Solr versions differ on layout:
            //   - Solr 8.9+ / Solr 9 (incremental, default): <backup_name>/<collection>/zk_backup_0/...
            //   - Solr 6 / 7 (non-incremental, only option): <backup_name>/snapshot.shardN/... (no <collection>)
            // We mirror <backup_location>/<backup_name>/ verbatim and let resolveCollectionDataDir
            // descend the right amount.
            var localBackupRoot = tempDir.toPath().resolve("cloud_backup_local");
            var containerBackupDir = backupLocation + "/cloud_backup";
            copyDirectoryFromContainer(solr, containerBackupDir, localBackupRoot);
            // Diagnostic: log the local copy structure so layout differences across versions are visible.
            try (var walk = java.nio.file.Files.walk(localBackupRoot, 3)) {
                walk.forEach(p -> log.atInfo().setMessage("Local backup tree: {}").addArgument(p).log());
            }

            var collectionDataDir = SolrBackupLayout.resolveCollectionDataDir(localBackupRoot);
            log.atInfo().setMessage("Resolved collection data dir for {} backup: {}")
                .addArgument(solrVersion).addArgument(collectionDataDir).log();

            // Parse schema from the backup's zk_backup directory (numbered or bare).
            var schema = SolrSchemaXmlParser.findAndParse(collectionDataDir);

            // Verify shard discovery — should find 2 shards
            var schemaNode = schema.path("schema");
            var source = new SolrBackupSource(collectionDataDir, collection, schemaNode, solrVersion.major());
            var partitions = source.listPartitions(collection);
            assertThat("Should discover " + numShards + " shards from SolrCloud backup",
                partitions.size(), equalTo(numShards));

            // Migrate all shards
            var targetClient = createOpenSearchClient(target);
            var sink = new OpenSearchDocumentSink(
                targetClient, null, false, DocumentExceptionAllowlist.empty(), null
            );
            var pipeline = new DocumentMigrationPipeline(source, sink, 100, Long.MAX_VALUE, numShards, 10);
            var cursors = pipeline.migrateAll().collectList().block();

            assertThat("Should have cursors from shards", cursors.size(), greaterThan(0));
            verifyDocCount(target, collection, 20);
        }
    }

    /**
     * E2E: SolrCloud metadata migration — verifies schema from backup's latest zk_backup_N
     * is correctly converted to OpenSearch mappings.
     */
    @ParameterizedTest(name = "solrcloud metadata: {0} → {1}")
    @MethodSource("solr8ToOpenSearch")
    void solrCloudBackupMetadataMigration(
        SolrClusterContainer.SolrVersion solrVersion,
        SearchClusterContainer.ContainerVersion targetVersion
    ) throws Exception {
        try (
            var solr = SolrClusterContainer.cloud(solrVersion);
            var target = new SearchClusterContainer(targetVersion)
        ) {
            solr.start();
            target.start();

            var collection = "schema_test";

            // Create SolrCloud collection with custom schema fields
            solr.execInContainer("curl", "-s",
                "http://localhost:8983/solr/admin/collections?action=CREATE"
                    + "&name=" + collection
                    + "&numShards=2&replicationFactor=1&maxShardsPerNode=2&wt=json");

            // Add typed fields
            solr.execInContainer("curl", "-s", "-H", "Content-Type: application/json",
                "http://localhost:8983/solr/" + collection + "/schema",
                "-d", "{\"add-field\":["
                    + "{\"name\":\"title\",\"type\":\"text_general\",\"stored\":true},"
                    + "{\"name\":\"count\",\"type\":\"pint\",\"stored\":true},"
                    + "{\"name\":\"created\",\"type\":\"pdate\",\"stored\":true},"
                    + "{\"name\":\"active\",\"type\":\"boolean\",\"stored\":true}"
                    + "]}");

            // Index a doc
            solr.execInContainer("curl", "-s", "-H", "Content-Type: application/json",
                "http://localhost:8983/solr/" + collection + "/update?commit=true",
                "-d", "[{\"id\":\"1\",\"title\":\"test\",\"count\":42,\"created\":\"2024-01-15T00:00:00Z\",\"active\":true}]");

            // Backup via Collections API
            var backupLocation = "/var/solr/data/backups";
            solr.execInContainer("mkdir", "-p", backupLocation);
            solr.execInContainer("curl", "-s",
                "http://localhost:8983/solr/admin/collections?action=BACKUP"
                    + "&name=meta_backup&collection=" + collection
                    + "&location=" + backupLocation + "&wt=json");

            // Copy backup
            var localBackupRoot = tempDir.toPath().resolve("meta_backup");
            copyDirectoryFromContainer(solr,
                backupLocation + "/meta_backup/" + collection,
                localBackupRoot.resolve(collection));

            // Parse schema from the latest zk_backup_N in the backup
            var schema = SolrSchemaXmlParser.findAndParse(localBackupRoot.resolve(collection));
            var schemaNode = schema.path("schema");

            // Convert to OpenSearch mappings
            var mappings = SolrSchemaConverter.convertToOpenSearchMappings(
                schemaNode.path("fields"),
                schemaNode.path("dynamicFields"),
                schemaNode.path("copyFields"),
                schemaNode.path("fieldTypes")
            );
            var properties = mappings.path("properties");
            log.atInfo().setMessage("SolrCloud backup schema mappings: {}").addArgument(properties).log();

            // Verify field type conversions from the backup schema
            assertThat("title → text", properties.path("title").path("type").asText(), equalTo("text"));
            assertThat("count → integer", properties.path("count").path("type").asText(), equalTo("integer"));
            assertThat("created → date", properties.path("created").path("type").asText(), equalTo("date"));
            assertThat("active → boolean", properties.path("active").path("type").asText(), equalTo("boolean"));

            // Also migrate the doc to verify end-to-end
            var source = new SolrBackupSource(localBackupRoot.resolve(collection), collection, schemaNode, solrVersion.major());
            var targetClient = createOpenSearchClient(target);
            var sink = new OpenSearchDocumentSink(
                targetClient, null, false, DocumentExceptionAllowlist.empty(), null
            );
            new DocumentMigrationPipeline(source, sink, 100, Long.MAX_VALUE)
                .migrateAll().collectList().block();

            verifyDocCount(target, collection, 1);
        }
    }

    /**
     * E2E (Solr 6 / 7 only): Trie* numeric and date fields are stored, read with the matching
     * Lucene reader, and mapped to integer/long/float/double/date in OpenSearch (AC1, AC5).
     * Trie* types were the default numeric types in Solr 6 and most of 7 before Point-based
     * types replaced them; Solr 9 removes them entirely, so this test is gated to 6/7.
     */
    @ParameterizedTest(name = "Trie* fields: {0} → {1}")
    @MethodSource("solr6And7ToOpenSearch")
    void trieFieldTypesMigrateCorrectly(
        SolrClusterContainer.SolrVersion solrVersion,
        SearchClusterContainer.ContainerVersion targetVersion
    ) throws Exception {
        try (
            var solr = new SolrClusterContainer(solrVersion);
            var target = new SearchClusterContainer(targetVersion)
        ) {
            solr.start();
            target.start();

            createSolrCollection(solr, COLLECTION_NAME);

            solr.execInContainer("curl", "-s", "-H", "Content-Type: application/json",
                "http://localhost:8983/solr/" + COLLECTION_NAME + "/schema",
                "-d", "{\"add-field\":["
                    + "{\"name\":\"trie_int\",   \"type\":\"tint\",    \"stored\":true},"
                    + "{\"name\":\"trie_long\",  \"type\":\"tlong\",   \"stored\":true},"
                    + "{\"name\":\"trie_float\", \"type\":\"tfloat\",  \"stored\":true},"
                    + "{\"name\":\"trie_double\",\"type\":\"tdouble\", \"stored\":true},"
                    + "{\"name\":\"trie_date\",  \"type\":\"tdate\",   \"stored\":true},"
                    // AC11: multi-valued strings field — must round-trip as JSON array in _source
                    + "{\"name\":\"tags\",       \"type\":\"strings\", \"stored\":true,\"docValues\":true,\"multiValued\":true}"
                    + "]}");

            var doc = "[{"
                + "\"id\":\"trie1\","
                + "\"trie_int\":42,"
                + "\"trie_long\":1234567890,"
                + "\"trie_float\":3.14,"
                + "\"trie_double\":2.718281828,"
                + "\"trie_date\":\"2024-01-15T10:30:00Z\","
                + "\"tags\":[\"alpha\",\"beta\",\"gamma\"]"
                + "}]";
            solr.execInContainer("curl", "-s", "-H", "Content-Type: application/json",
                "http://localhost:8983/solr/" + COLLECTION_NAME + "/update?commit=true",
                "-d", doc);

            var schema = fetchSolrSchema(solr, COLLECTION_NAME);
            var backupDir = createAndCopyBackup(solr, COLLECTION_NAME);

            var source = new SolrBackupSource(backupDir, COLLECTION_NAME, schema, solrVersion.major());
            var targetClient = createOpenSearchClient(target);
            var sink = new OpenSearchDocumentSink(
                targetClient, null, false, DocumentExceptionAllowlist.empty(), null
            );
            new DocumentMigrationPipeline(source, sink, 100, Long.MAX_VALUE)
                .migrateAll().collectList().block();

            var restClient = createRestClient(target);
            var ctx = DocumentMigrationTestContext.factory().noOtelTracking();
            restClient.get("_refresh", ctx.createUnboundRequestContext());

            // AC5: Trie* mapping types resolve to integer/long/float/double/date
            var mappingResp = restClient.get(
                COLLECTION_NAME + "/_mapping", ctx.createUnboundRequestContext()
            );
            var properties = MAPPER.readTree(mappingResp.body)
                .path(COLLECTION_NAME).path("mappings").path("properties");

            // AC5: Trie* mapping types resolve to OpenSearch types.
            // Solr 7's managed-schema promotes tint→tlong and tfloat→tdouble, so integer/float
            // only round-trips on Solr 6; on Solr 7 the schema reports long/double.
            boolean isSolr7 = solrVersion.major() == 7;
            assertThat("trie_int type",
                properties.path("trie_int").path("type").asText(),
                equalTo(isSolr7 ? "long" : "integer"));
            assertThat("trie_long → long", properties.path("trie_long").path("type").asText(), equalTo("long"));
            assertThat("trie_float type",
                properties.path("trie_float").path("type").asText(),
                equalTo(isSolr7 ? "double" : "float"));
            assertThat("trie_double → double", properties.path("trie_double").path("type").asText(), equalTo("double"));
            assertThat("trie_date → date", properties.path("trie_date").path("type").asText(), equalTo("date"));
            // AC5: date format — OpenSearch 2.x omits format in _mapping when it equals the default
            // (strict_date_optional_time||epoch_millis). We verify it is either that value or absent.
            var dateFormat = properties.path("trie_date").path("format").asText();
            assertTrue(
                dateFormat.isEmpty() || dateFormat.equals("strict_date_optional_time||epoch_millis"),
                "trie_date format should be default or explicit, got: " + dateFormat
            );

            // AC1: doc values land with correct types in _source
            var searchResp = restClient.get(
                COLLECTION_NAME + "/_search?q=id:trie1&size=1", ctx.createUnboundRequestContext()
            );
            var hits = MAPPER.readTree(searchResp.body).path("hits").path("hits");
            assertThat("Should find trie1", hits.size(), equalTo(1));
            var migrated = hits.get(0).path("_source");
            assertThat("trie_int value", migrated.path("trie_int").asInt(), equalTo(42));
            assertThat("trie_long value", migrated.path("trie_long").asLong(), equalTo(1234567890L));

            // AC11: multi-valued field round-trips as JSON array, not concatenated string
            assertTrue(migrated.path("tags").isArray(), "tags should be a JSON array in _source");
            assertThat("tags array size", migrated.path("tags").size(), equalTo(3));
            var tagValues = new HashSet<String>();
            migrated.path("tags").forEach(v -> tagValues.add(v.asText()));
            assertTrue(tagValues.contains("alpha"), "tags should contain alpha");
            assertTrue(tagValues.contains("beta"), "tags should contain beta");
            assertTrue(tagValues.contains("gamma"), "tags should contain gamma");
        }
    }

    // --- Helpers ---

    /** POST a JSON document array to a Solr collection's /update endpoint with commit. */
    private static void indexSolrDocs(SolrClusterContainer solr, String collection, String jsonDocsArray)
        throws Exception {
        solr.execInContainer(
            "curl", "-s", "-H", "Content-Type: application/json",
            "http://localhost:8983/solr/" + collection + "/update?commit=true",
            "-d", jsonDocsArray
        );
    }

    /** POST {@code {"add-field":[...]}} to a Solr collection's Schema API. */
    private static void addSolrSchemaFields(
        SolrClusterContainer solr, String collection, String addFieldArrayJson
    ) throws Exception {
        solr.execInContainer(
            "curl", "-s", "-H", "Content-Type: application/json",
            "http://localhost:8983/solr/" + collection + "/schema",
            "-d", "{\"add-field\":" + addFieldArrayJson + "}"
        );
    }

    /** Snapshot the Solr collection, copy locally, and run the in-process pipeline to OpenSearch. */
    private void runPipelineMigration(
        SolrClusterContainer solr, SearchClusterContainer target,
        String collection, SolrClusterContainer.SolrVersion solrVersion
    ) throws Exception {
        var schema = fetchSolrSchema(solr, collection);
        var backupDir = createAndCopyBackup(solr, collection);
        var source = new SolrBackupSource(backupDir, collection, schema, solrVersion.major());
        var sink = new OpenSearchDocumentSink(
            createOpenSearchClient(target), null, false, DocumentExceptionAllowlist.empty(), null
        );
        new DocumentMigrationPipeline(source, sink, 100, Long.MAX_VALUE).migrateAll().collectList().block();
    }

    /** Assert that {@code _field_caps} reports {@code field} on the target index with the given OS type. */
    private static void assertTargetFieldType(
        RestClient restClient, DocumentMigrationTestContext ctx,
        String index, String field, String expectedType
    ) throws Exception {
        var resp = restClient.get(index + "/_field_caps?fields=" + field, ctx.createUnboundRequestContext());
        var typeNode = MAPPER.readTree(resp.body)
            .path("fields").path(field).path(expectedType).path("type");
        assertThat(field + " → " + expectedType, typeNode.asText(), equalTo(expectedType));
    }

    /** Fetch the {@code _source} of a target doc by id. Asserts the doc exists. */
    private static JsonNode fetchTargetSource(
        RestClient restClient, DocumentMigrationTestContext ctx, String index, String id
    ) throws Exception {
        var resp = restClient.get(index + "/_search?q=id:" + id + "&size=1", ctx.createUnboundRequestContext());
        var hits = MAPPER.readTree(resp.body).path("hits").path("hits");
        assertThat("Should find " + id, hits.size(), equalTo(1));
        var src = hits.get(0).path("_source");
        log.atInfo().setMessage("Migrated _source for {}: {}").addArgument(id).addArgument(src).log();
        return src;
    }

    /** Run a Lucene query string against the target and assert exactly one hit with the expected id. */
    private static void assertQueryReturnsId(
        RestClient restClient, DocumentMigrationTestContext ctx,
        String index, String luceneQuery, String expectedId
    ) throws Exception {
        var resp = restClient.get(
            index + "/_search?q=" + luceneQuery + "&size=10", ctx.createUnboundRequestContext()
        );
        var hits = MAPPER.readTree(resp.body).path("hits").path("hits");
        assertThat("query " + luceneQuery + " should return one hit", hits.size(), equalTo(1));
        assertThat("query " + luceneQuery + " should return id=" + expectedId,
            hits.get(0).path("_id").asText(), equalTo(expectedId));
    }

    /**
     * Polls /solr/admin/collections?action=CLUSTERSTATUS until the collection has the
     * expected number of active shards. Solr 6's CREATE returns before the collection is
     * fully ready, so a subsequent action (BACKUP, etc.) can race and fail.
     */
    private static void waitForCollectionActive(
        SolrClusterContainer solr, String collection, int expectedShards, int maxSeconds
    ) throws Exception {
        for (int i = 0; i < maxSeconds; i++) {
            var status = solr.execInContainer("curl", "-s",
                "http://localhost:8983/solr/admin/collections?action=CLUSTERSTATUS&collection="
                    + collection + "&wt=json");
            var body = status.getStdout();
            // Count occurrences of "state":"active" — one per active replica.
            int activeCount = 0;
            int idx = 0;
            while ((idx = body.indexOf("\"state\":\"active\"", idx)) != -1) {
                activeCount++;
                idx++;
            }
            if (activeCount >= expectedShards) {
                log.atInfo().setMessage("Collection {} ready with {} active replica(s) after {}s")
                    .addArgument(collection).addArgument(activeCount).addArgument(i).log();
                return;
            }
            Thread.sleep(1000);
        }
        var finalStatus = solr.execInContainer("curl", "-s",
            "http://localhost:8983/solr/admin/collections?action=CLUSTERSTATUS&collection="
                + collection + "&wt=json");
        throw new IllegalStateException(
            "Collection " + collection + " did not become active within " + maxSeconds
                + "s. Last CLUSTERSTATUS:\n" + finalStatus.getStdout());
    }

    /**
     * Recursively copies a directory tree from a container to a local path.
     * Uses 'find' to list all files, then copies each one individually.
     */
    private static void copyDirectoryFromContainer(
        SolrClusterContainer solr, String containerDir, Path localDir
    ) throws Exception {
        Files.createDirectories(localDir);
        var findResult = solr.execInContainer("find", containerDir, "-type", "f");
        for (var line : findResult.getStdout().trim().split("\n")) {
            if (line.isEmpty()) continue;
            var relativePath = line.substring(containerDir.length());
            if (relativePath.startsWith("/")) relativePath = relativePath.substring(1);
            var localFile = localDir.resolve(relativePath);
            Files.createDirectories(localFile.getParent());
            solr.copyFileFromContainer(line, localFile.toString());
        }
        log.atInfo().setMessage("Copied {} to {}").addArgument(containerDir).addArgument(localDir).log();
    }

    private static void populateSolrDocuments(SolrClusterContainer solr, String collection, int count)
        throws Exception {
        populateSolrDocuments(solr, collection, count, "doc", null, null);
    }

    private static void populateSolrDocuments(SolrClusterContainer solr, String collection, int count, String idPrefix)
        throws Exception {
        populateSolrDocuments(solr, collection, count, idPrefix, null, null);
    }

    private static void populateSolrDocuments(
        SolrClusterContainer solr, String collection, int count, String idPrefix, String user, String pass
    ) throws Exception {
        var sb = new StringBuilder("[");
        for (int i = 1; i <= count; i++) {
            if (i > 1) sb.append(",");
            sb.append(String.format(
                "{\"id\":\"%s%d\",\"title_s\":\"Document %d\",\"value_i\":%d,\"active_b\":true}",
                idPrefix, i, i, i
            ));
        }
        sb.append("]");
        var curlCmd = new ArrayList<String>();
        curlCmd.add("curl");
        curlCmd.add("-s");
        if (user != null && pass != null) {
            curlCmd.add("-u");
            curlCmd.add(user + ":" + pass);
        }
        curlCmd.add("-H");
        curlCmd.add("Content-Type: application/json");
        curlCmd.add("http://localhost:8983/solr/" + collection + "/update?commit=true");
        curlCmd.add("-d");
        curlCmd.add(sb.toString());
        solr.execInContainer(curlCmd.toArray(new String[0]));
    }

    private static void addSchemaFields(SolrClusterContainer solr, String collection) throws Exception {
        var schemaUpdate = "{"
            + "\"add-field\": ["
            + "  {\"name\":\"stored_keyword\",   \"type\":\"string\",      \"stored\":true,  \"docValues\":true,  \"indexed\":true},"
            + "  {\"name\":\"unstored_keyword\", \"type\":\"string\",      \"stored\":false, \"docValues\":true,  \"indexed\":true},"
            + "  {\"name\":\"stored_text\",      \"type\":\"text_general\", \"stored\":true,  \"indexed\":true},"
            + "  {\"name\":\"unstored_text\",    \"type\":\"text_general\", \"stored\":false, \"indexed\":true},"
            + "  {\"name\":\"stored_int\",       \"type\":\"pint\",         \"stored\":true,  \"docValues\":true,  \"indexed\":true},"
            + "  {\"name\":\"unstored_int\",     \"type\":\"pint\",         \"stored\":false, \"docValues\":true,  \"indexed\":true},"
            + "  {\"name\":\"stored_long\",      \"type\":\"plong\",        \"stored\":true,  \"docValues\":true},"
            + "  {\"name\":\"stored_float\",     \"type\":\"pfloat\",       \"stored\":true,  \"docValues\":true},"
            + "  {\"name\":\"stored_double\",    \"type\":\"pdouble\",      \"stored\":true,  \"docValues\":true},"
            + "  {\"name\":\"stored_date\",      \"type\":\"pdate\",        \"stored\":true,  \"docValues\":true},"
            + "  {\"name\":\"stored_bool\",      \"type\":\"boolean\",      \"stored\":true,  \"docValues\":true},"
            + "  {\"name\":\"unstored_bool\",    \"type\":\"boolean\",      \"stored\":false, \"docValues\":true},"
            + "  {\"name\":\"stored_binary\",    \"type\":\"binary\",       \"stored\":true},"
            + "  {\"name\":\"multi_string\",     \"type\":\"strings\",      \"stored\":true,  \"docValues\":true, \"multiValued\":true},"
            + "  {\"name\":\"nodv_keyword\",     \"type\":\"string\",       \"stored\":true,  \"docValues\":false, \"indexed\":true}"
            + "]}";
        solr.execInContainer(
            "curl", "-s", "-H", "Content-Type: application/json",
            "http://localhost:8983/solr/" + collection + "/schema",
            "-d", schemaUpdate
        );
    }

    private static void indexRichDocument(SolrClusterContainer solr, String collection) throws Exception {
        var doc = "[{"
            + "\"id\": \"doc1\","
            + "\"stored_keyword\": \"hello\","
            + "\"unstored_keyword\": \"invisible\","
            + "\"stored_text\": \"full text search\","
            + "\"unstored_text\": \"not stored\","
            + "\"stored_int\": 42,"
            + "\"unstored_int\": 99,"
            + "\"stored_long\": 1234567890,"
            + "\"stored_float\": 3.14,"
            + "\"stored_double\": 2.718281828,"
            + "\"stored_date\": \"2024-01-15T10:30:00Z\","
            + "\"stored_bool\": true,"
            + "\"unstored_bool\": false,"
            + "\"stored_binary\": \"SGVsbG8gV29ybGQ=\","
            + "\"multi_string\": [\"alpha\", \"beta\", \"gamma\"],"
            + "\"nodv_keyword\": \"no-docvalues\""
            + "}]";
        solr.execInContainer(
            "curl", "-s", "-H", "Content-Type: application/json",
            "http://localhost:8983/solr/" + collection + "/update?commit=true",
            "-d", doc
        );
    }

    private static JsonNode fetchSolrSchema(SolrClusterContainer solr, String collection) throws Exception {
        var result = solr.execInContainer(
            "curl", "-s", "http://localhost:8983/solr/" + collection + "/schema?wt=json"
        );
        return MAPPER.readTree(result.getStdout()).path("schema");
    }

    private Path createAndCopyBackup(SolrClusterContainer solr, String collection) throws Exception {
        return createAndCopyBackup(solr, collection, "migration_backup");
    }

    private Path createAndCopyBackup(SolrClusterContainer solr, String collection, String backupName)
        throws Exception {
        var probe = solr.execInContainer("sh", "-c",
            "for d in /var/solr/data /opt/solr/server/solr; do "
            + "  if [ -d \"$d\" ] && [ -w \"$d\" ]; then echo \"$d\"; break; fi; "
            + "done");
        var solrDataDir = probe.getStdout().trim();
        if (solrDataDir.isEmpty()) {
            throw new RuntimeException("No writable Solr data directory found in container");
        }
        log.atInfo().setMessage("Solr writable data dir: {}").addArgument(solrDataDir).log();

        var trigger = solr.execInContainer(
            "curl", "-s",
            "http://localhost:8983/solr/" + collection
                + "/replication?command=backup&location=" + solrDataDir + "&name=" + backupName
        );
        log.atInfo().setMessage("Backup trigger response: {}").addArgument(trigger.getStdout()).log();

        var snapshotDir = solrDataDir + "/snapshot." + backupName;
        // Poll directly on the filesystem signal that backup is complete: a segments_* file
        // landed in the snapshot directory. This works across Solr 6/7/8/9 regardless of
        // the JSON response format of /replication?command=details.
        boolean ready = false;
        for (int i = 0; i < 60; i++) {
            var find = solr.execInContainer("sh", "-c",
                "find " + snapshotDir + " -name 'segments_*' -type f 2>/dev/null | head -1");
            if (!find.getStdout().trim().isEmpty()) {
                ready = true;
                break;
            }
            Thread.sleep(1000);
        }
        if (!ready) {
            var listing = solr.execInContainer("sh", "-c",
                "ls -laR " + solrDataDir + " 2>&1 | head -200");
            throw new IllegalStateException(
                "Backup did not produce a segments_* file under " + snapshotDir + " within 60s.\n"
                + "Trigger response: " + trigger.getStdout() + "\n"
                + "Container " + solrDataDir + " listing:\n" + listing.getStdout());
        }

        var localBackupDir = tempDir.toPath().resolve("solr_backup_" + backupName);
        Files.createDirectories(localBackupDir);

        // Recursive copy. Standalone replication backup is normally flat, but copying
        // recursively makes the helper robust to layout differences across Solr versions.
        var findResult = solr.execInContainer("find", snapshotDir, "-type", "f");
        int filesCopied = 0;
        for (var line : findResult.getStdout().trim().split("\n")) {
            if (line.isEmpty()) continue;
            var rel = line.substring(snapshotDir.length()).replaceFirst("^/", "");
            var localFile = localBackupDir.resolve(rel);
            var parent = localFile.getParent();
            if (parent != null) Files.createDirectories(parent);
            solr.copyFileFromContainer(line, localFile.toString());
            filesCopied++;
        }
        log.atInfo().setMessage("Copied {} backup files from {} to {}")
            .addArgument(filesCopied).addArgument(snapshotDir).addArgument(localBackupDir).log();
        return localBackupDir;
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        Files.createDirectories(target);
        try (var stream = Files.list(source)) {
            stream.forEach(p -> {
                try {
                    Files.copy(p, target.resolve(p.getFileName()));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private static void createSolrCollection(SolrClusterContainer solr, String name) throws Exception {
        var result = solr.execInContainer("solr", "create_core", "-c", name);
        if (result.getExitCode() != 0) {
            throw new RuntimeException("Failed to create Solr core: " + result.getStderr());
        }
    }

    private static org.opensearch.migrations.bulkload.common.OpenSearchClient createOpenSearchClient(
        SearchClusterContainer cluster
    ) {
        return new OpenSearchClientFactory(
            ConnectionContextTestParams.builder().host(cluster.getUrl()).build().toConnectionContext()
        ).determineVersionAndCreate();
    }

    private static RestClient createRestClient(SearchClusterContainer cluster) {
        return new RestClient(
            ConnectionContextTestParams.builder().host(cluster.getUrl()).build().toConnectionContext()
        );
    }

    private static void verifyDocCount(SearchClusterContainer cluster, String indexName, int expected) {
        var context = DocumentMigrationTestContext.factory().noOtelTracking();
        var restClient = createRestClient(cluster);
        restClient.get("_refresh", context.createUnboundRequestContext());
        assertEquals(
            expected,
            new SearchClusterRequests(context)
                .getMapOfIndexAndDocCount(restClient)
                .getOrDefault(indexName, 0),
            "Expected " + expected + " docs in " + indexName
        );
    }
}
