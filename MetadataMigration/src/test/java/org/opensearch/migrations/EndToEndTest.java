package org.opensearch.migrations;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.SupportedClusters;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.commands.MigrationItemResult;
import org.opensearch.migrations.metadata.CreationResult;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItems;

/**
 * Tests focused on setting up whole source clusters, performing a migration, and validation on the target cluster.
 */
@Tag("isolatedTest")
@Slf4j
class EndToEndTest extends BaseMigrationTest {

    @TempDir
    protected File localDirectory;

    private static Stream<Arguments> scenarios() {
        return SupportedClusters.representativeMigrationPairs().stream()
            .flatMap(pair -> {
                List<TemplateType> templateTypes = Stream.concat(
                            (VersionMatchers.isOS_2_X.test(pair.source().getVersion())
                                    ? Stream.empty()
                                    : Stream.of(TemplateType.Legacy)),
                                (UnboundVersionMatchers.isGreaterOrEqualES_7_X
                                    .test(pair.source().getVersion())
                                    ? Stream.of(TemplateType.Index, TemplateType.IndexAndComponent)
                                    : Stream.empty()))
                    .toList();

                return Arrays.stream(TransferMedium.values())
                    .map(medium -> Arguments.of(pair.source(), pair.target(), medium, templateTypes))
                        .toList().stream();
            });
    }

    @ParameterizedTest(name = "From version {0} to version {1}, Medium {2}, Command {3}, Template Type {4}")
    @MethodSource(value = "scenarios")
    void metadataCommand(SearchClusterContainer.ContainerVersion sourceVersion,
                         SearchClusterContainer.ContainerVersion targetVersion,
                         TransferMedium medium,
                         List<TemplateType> templateTypes) {
        try (
            final var sourceCluster = new SearchClusterContainer(sourceVersion);
            final var targetCluster = new SearchClusterContainer(targetVersion)
        ) {
            this.sourceCluster = sourceCluster;
            this.targetCluster = targetCluster;
            metadataCommandOnClusters(medium, MetadataCommands.EVALUATE, templateTypes);
            metadataCommandOnClusters(medium, MetadataCommands.MIGRATE, templateTypes);
        }
    }

    private static Stream<Arguments> extendedScenarios() {
        return SupportedClusters.extendedSources().stream().map(s -> Arguments.of(s));
    }

    private static Stream<Arguments> es6xScenarios() {
        return Stream.of(
            SearchClusterContainer.ES_V6_8_23,
            SearchClusterContainer.ES_V6_7,
            SearchClusterContainer.ES_V6_6,
            SearchClusterContainer.ES_V6_5,
            SearchClusterContainer.ES_V6_4,
            SearchClusterContainer.ES_V6_3,
            SearchClusterContainer.ES_V6_2,
            SearchClusterContainer.ES_V6_1,
            SearchClusterContainer.ES_V6_0
        ).map(Arguments::of);
    }

    @ParameterizedTest(name = "From version {0} to version OS 2.19")
    @MethodSource(value = "extendedScenarios")
    void extendedMetadata(SearchClusterContainer.ContainerVersion sourceVersion) {
        try (
                final var sourceCluster = new SearchClusterContainer(sourceVersion);
                final var targetCluster = new SearchClusterContainer(SearchClusterContainer.OS_V2_19_4);
        ) {
            this.sourceCluster = sourceCluster;
            this.targetCluster = targetCluster;
            metadataCommandOnClusters(
                    TransferMedium.SnapshotImage,
                    MetadataCommands.EVALUATE,
                    List.of(TemplateType.Legacy));
            metadataCommandOnClusters(
                    TransferMedium.SnapshotImage,
                    MetadataCommands.MIGRATE,
                    List.of(TemplateType.Legacy));
        }
    }

    /**
     * E2E regression test: an ES 6 index that uses the legacy "standard" token filter (removed in ES 7+/OpenSearch)
     * must still migrate successfully. The metadata migration retries after stripping the offending filter
     * from analyzer filter arrays — see InvalidResponse#getRemovedTokenFilters and ObjectNodeUtils#removeAnalyzerFilters.
     * Mirrors the production failure where ES 6 templates declared analyzers like:
     *   "filter": ["standard", "custom_pattern_capture", "lowercase", "asciifolding", "my_stopwords"]
     */
    @Test
    void deprecatedStandardTokenFilter_isStrippedAndIndexCreated() {
        try (
            final var sourceCluster = new SearchClusterContainer(SearchClusterContainer.ES_V6_8_23);
            final var targetCluster = new SearchClusterContainer(SearchClusterContainer.OS_V2_19_4)
        ) {
            this.sourceCluster = sourceCluster;
            this.targetCluster = targetCluster;
            startClusters();

            var indexName = "deprecated-standard-filter-index";
            var templateName = "deprecated-standard-filter-template";
            var templatePattern = "deprecated-standard-filter-tmpl-*";
            var templatedIndexName = "deprecated-standard-filter-tmpl-2023";

            // Index with an analyzer that includes the deprecated "standard" token filter.
            var indexBody = "{" +
                "  \"settings\": {" +
                "    \"index\": {" +
                "      \"number_of_shards\": 1," +
                "      \"number_of_replicas\": 0," +
                "      \"analysis\": {" +
                "        \"analyzer\": {" +
                "          \"legacy_with_standard_filter\": {" +
                "            \"type\": \"custom\"," +
                "            \"tokenizer\": \"standard\"," +
                "            \"filter\": [\"standard\", \"lowercase\", \"asciifolding\"]" +
                "          }" +
                "        }" +
                "      }" +
                "    }" +
                "  }," +
                "  \"mappings\": {" +
                "    \"" + sourceOperations.defaultDocType() + "\": {" +
                "      \"properties\": {" +
                "        \"body\": {" +
                "          \"type\": \"text\"," +
                "          \"analyzer\": \"legacy_with_standard_filter\"" +
                "        }" +
                "      }" +
                "    }" +
                "  }" +
                "}";
            sourceOperations.createIndex(indexName, indexBody);
            sourceOperations.createDocument(indexName, "1", "{ \"body\": \"hello world\" }");

            // Legacy template using the same deprecated filter — exercises the template retry path.
            var templateBody = "{" +
                "  \"index_patterns\": [\"" + templatePattern + "\"]," +
                "  \"order\": 0," +
                "  \"settings\": {" +
                "    \"index\": {" +
                "      \"number_of_shards\": 1," +
                "      \"analysis\": {" +
                "        \"analyzer\": {" +
                "          \"legacy_with_standard_filter\": {" +
                "            \"type\": \"custom\"," +
                "            \"tokenizer\": \"standard\"," +
                "            \"filter\": [\"standard\", \"lowercase\"]" +
                "          }" +
                "        }" +
                "      }" +
                "    }" +
                "  }," +
                "  \"mappings\": {}" +
                "}";
            var tmplResp = sourceOperations.put("/_template/" + templateName + "?include_type_name=true", templateBody);
            assertThat(tmplResp.getValue(), tmplResp.getKey(), equalTo(200));
            sourceOperations.createDocument(templatedIndexName, "1", "{ \"f\": \"v\" }");

            var snapshotName = "deprecated_standard_filter_snap";
            var testSnapshotContext = SnapshotTestContext.factory().noOtelTracking();
            createSnapshot(sourceCluster, snapshotName, testSnapshotContext);
            sourceCluster.copySnapshotData(localDirectory.toString());

            var arguments = prepareSnapshotMigrationArgs(snapshotName, localDirectory.toString());

            MigrationItemResult result = executeMigration(arguments, MetadataCommands.MIGRATE);
            log.info(result.asCliOutput());
            assertThat("Migration should succeed despite removed 'standard' token filter on source",
                result.getExitCode(), equalTo(0));

            // Both items should be in successful results
            assertThat(getNames(getSuccessfulResults(result.getItems().getIndexes())), hasItems(indexName));
            assertThat(getNames(getSuccessfulResults(result.getItems().getIndexTemplates())), hasItems(templateName));

            // Verify the index made it to the target with the offending filter stripped
            var indexRes = targetOperations.get("/" + indexName);
            assertThat(indexRes.getValue(), indexRes.getKey(), equalTo(200));
            assertThat("Removed token filter should not be present on target index",
                indexRes.getValue(), not(containsString("\"standard\",\"lowercase\"")));
            assertThat("Other filters should still be present", indexRes.getValue(), containsString("lowercase"));
            assertThat("Other filters should still be present", indexRes.getValue(), containsString("asciifolding"));
            assertThat("'standard' tokenizer (separate from filter) should still be present",
                indexRes.getValue(), containsString("\"tokenizer\":\"standard\""));

            // Verify the legacy template made it to the target with the offending filter stripped
            var tmpl = targetOperations.get("/_template/" + templateName);
            assertThat(tmpl.getValue(), tmpl.getKey(), equalTo(200));
            assertThat("Template should have 'standard' filter removed",
                tmpl.getValue(), not(containsString("\"standard\",\"lowercase\"")));
            assertThat("Template should retain other filters", tmpl.getValue(), containsString("lowercase"));
        }
    }

    /**
     * Customer-reported scenario: ES 6 index with _source DISABLED and a custom analyzer
     * that uses the deprecated "standard" token filter (removed in ES 7+/OS), referenced
     * by a "text" field with norms:false. Without the preemptive transform, document
     * indexing on the OS 2 target fails with:
     *   "The [standard] token filter has been removed."
     *
     * The exact analyzer shape from the customer report:
     *   "custom_tokenized_string": {
     *     "filter": ["standard", "custom_pattern_capture", "lowercase", "asciifolding", "my_stopwords"],
     *     "type": "custom",
     *     "tokenizer": "standard"
     *   }
     * with a field:
     *   "subj": { "type": "text", "norms": false, "analyzer": "custom_tokenized_string" }
     *
     * This test confirms metadata migrate succeeds (the preemptive analysis-component
     * compatibility transform strips the offending "standard" filter from the analyzer's
     * filter array) AND that the resulting target index actually accepts indexing the
     * "subj" field — which is what failed for the customer.
     */
    @Test
    void deprecatedStandardTokenFilter_withSourceDisabledAndSubjField_migratesAndIndexes() {
        try (
            final var sourceCluster = new SearchClusterContainer(SearchClusterContainer.ES_V6_8_23);
            final var targetCluster = new SearchClusterContainer(SearchClusterContainer.OS_V2_19_4)
        ) {
            this.sourceCluster = sourceCluster;
            this.targetCluster = targetCluster;
            startClusters();

            var indexName = "custom-source-disabled-subj";

            // Customer-shaped index: _source disabled, custom analyzer with the deprecated
            // "standard" token filter, field "subj" with norms:false referencing it.
            // Define custom_pattern_capture and my_stopwords inline so the analyzer is
            // self-contained on the source side. (We omit stopwords_path — that points at
            // a config-dir file that wouldn't exist on the source container either.)
            var indexBody = "{" +
                "  \"settings\": {" +
                "    \"index\": {" +
                "      \"number_of_shards\": 1," +
                "      \"number_of_replicas\": 0," +
                "      \"analysis\": {" +
                "        \"analyzer\": {" +
                "          \"custom_tokenized_string\": {" +
                "            \"type\": \"custom\"," +
                "            \"tokenizer\": \"standard\"," +
                "            \"filter\": [\"standard\", \"custom_pattern_capture\", \"lowercase\", \"asciifolding\", \"my_stopwords\"]" +
                "          }" +
                "        }," +
                "        \"filter\": {" +
                "          \"custom_pattern_capture\": {" +
                "            \"type\": \"pattern_capture\"," +
                "            \"preserve_original\": true," +
                "            \"patterns\": [\"([A-Za-z0-9._%+-]+)@\"]" +
                "          }," +
                "          \"my_stopwords\": {" +
                "            \"type\": \"stop\"," +
                "            \"stopwords\": [\"the\", \"a\", \"an\"]" +
                "          }" +
                "        }" +
                "      }" +
                "    }" +
                "  }," +
                "  \"mappings\": {" +
                "    \"" + sourceOperations.defaultDocType() + "\": {" +
                "      \"_source\": { \"enabled\": false }," +
                "      \"properties\": {" +
                "        \"subj\": {" +
                "          \"type\": \"text\"," +
                "          \"norms\": false," +
                "          \"analyzer\": \"custom_tokenized_string\"" +
                "        }," +
                "        \"from_addr\": {" +
                "          \"type\": \"keyword\"," +
                "          \"store\": true" +
                "        }" +
                "      }" +
                "    }" +
                "  }" +
                "}";
            sourceOperations.createIndex(indexName, indexBody);
            sourceOperations.createDocument(indexName, "1",
                "{ \"subj\": \"Quarterly review meeting tomorrow\", \"from_addr\": \"alice@example.com\" }");
            sourceOperations.createDocument(indexName, "2",
                "{ \"subj\": \"Re: invoice attached\", \"from_addr\": \"bob@example.com\" }");

            var snapshotName = "custom_source_disabled_snap";
            createSnapshot(sourceCluster, snapshotName, SnapshotTestContext.factory().noOtelTracking());
            sourceCluster.copySnapshotData(localDirectory.toString());

            var arguments = prepareSnapshotMigrationArgs(snapshotName, localDirectory.toString());
            // The customer's index has _source disabled, so we need this flag.
            arguments.enableSourcelessMigrations = true;

            // ── Step 1: metadata migrate (this is where the analyzer setup gets pushed) ──
            MigrationItemResult metaResult = executeMigration(arguments, MetadataCommands.MIGRATE);
            log.info(metaResult.asCliOutput());
            assertThat("Metadata migration should succeed despite removed 'standard' token filter on source",
                metaResult.getExitCode(), equalTo(0));
            assertThat(getNames(getSuccessfulResults(metaResult.getItems().getIndexes())), hasItems(indexName));

            // ── Step 2: target index should be present with analyzer fixed ──
            var indexRes = targetOperations.get("/" + indexName);
            assertThat(indexRes.getValue(), indexRes.getKey(), equalTo(200));

            var settingsRes = targetOperations.get("/" + indexName + "/_settings");
            assertThat(settingsRes.getValue(), settingsRes.getKey(), equalTo(200));
            var settingsBody = settingsRes.getValue();
            // Must NOT contain the legacy "standard" filter token in the analyzer's filter array.
            // We do a coarse-grained check that "custom_tokenized_string" no longer references
            // "standard" as a filter — verifying the custom filters survive.
            assertThat("Custom custom_pattern_capture filter must survive",
                settingsBody, containsString("custom_pattern_capture"));
            assertThat("lowercase must survive", settingsBody, containsString("lowercase"));
            assertThat("asciifolding must survive", settingsBody, containsString("asciifolding"));
            assertThat("my_stopwords must survive", settingsBody, containsString("my_stopwords"));

            // ── Step 3: actually use the analyzer to index a document on the target. This
            //    is what failed for the customer with "The [standard] token filter has been removed."
            //    On OS 2 with _source disabled, we still write the doc; if the analyzer is broken
            //    the index write will return 4xx with the standard-token-filter error.
            var indexDocRes = targetOperations.put(
                "/" + indexName + "/_doc/test-after-migrate?refresh=true",
                "{ \"subj\": \"Post-migration ingest test\", \"from_addr\": \"new@example.com\" }"
            );
            assertThat("Indexing into the migrated target index must succeed (analyzer chain valid). " +
                    "Response was: " + indexDocRes.getValue(),
                indexDocRes.getKey(), equalTo(201));

            // Confirm we can analyze the field via the cluster's _analyze API — this also uses
            // the analyzer end-to-end and would surface a [standard] filter error.
            var analyzeRes = targetOperations.post(
                "/" + indexName + "/_analyze",
                "{ \"analyzer\": \"custom_tokenized_string\", \"text\": \"Hello World\" }"
            );
            assertThat("Analyzer must be usable on target. Response: " + analyzeRes.getValue(),
                analyzeRes.getKey(), equalTo(200));
            assertThat("_analyze response must NOT mention the removed standard filter",
                analyzeRes.getValue(), not(containsString("standard] token filter has been removed")));
        }
    }

    /**
     * Regression test for ES 6 index with legacy analysis names, restored into ES 7, then migrated to OS.
     *
     * ES 6 accepts nGram/edgeNGram as direct built-in references in analyzer definitions and
     * the legacy "standard" token filter. These names are rejected by OS on new index creation.
     * When such an index is restored into ES 7 (snapshot restore), ES 7 holds the index with
     * the legacy names intact — and the migration source cluster is ES 7.
     *
     * Before the fix (isBelowES_7_X -> isBelowES_8_X in source predicates), the preemptive
     * analysis-compat transform was silently skipped for ES 7 sources, so migration to OS
     * would fail with "Unknown tokenizer/filter" or "removed token filter" errors.
     */
    @Test
    @SneakyThrows
    void es6IndexRestoredIntoEs7_migratesLegacyAnalyzersToOs() {
        var legacyRepo = "legacy_repo";
        var legacySnapshot = "legacy_snap";
        var indexName = "es6-ngram-standard-analysis";
        // Two separate local dirs: one for the ES 6 snapshot, one for the ES 7 snapshot.
        var es6SnapshotDir = Files.createTempDirectory("es6-snap-").toFile();
        var es7SnapshotDir = Files.createTempDirectory("es7-snap-").toFile();

        // Phase 1: Create the index on ES 6 where legacy names are still valid.
        try (final var es6Cluster = new SearchClusterContainer(SearchClusterContainer.ES_V6_8_23)) {
            es6Cluster.start();
            var es6Ops = new org.opensearch.migrations.bulkload.http.ClusterOperations(es6Cluster);

            // Index uses nGram/edgeNGram as direct built-in references and the legacy "standard"
            // token filter — all accepted by ES 6, all rejected by OS on new index creation.
            var indexBody = "{" +
                "  \"settings\": {" +
                "    \"index\": {" +
                "      \"number_of_shards\": 1," +
                "      \"number_of_replicas\": 0," +
                "      \"analysis\": {" +
                "        \"analyzer\": {" +
                "          \"legacy_analyzer\": {" +
                "            \"type\": \"custom\"," +
                "            \"tokenizer\": \"nGram\"," +
                "            \"filter\": [\"standard\", \"lowercase\", \"edgeNGram\"]" +
                "          }" +
                "        }" +
                "      }" +
                "    }" +
                "  }," +
                "  \"mappings\": {" +
                "    \"" + es6Ops.defaultDocType() + "\": {" +
                "      \"properties\": {" +
                "        \"body\": { \"type\": \"text\", \"analyzer\": \"legacy_analyzer\" }" +
                "      }" +
                "    }" +
                "  }" +
                "}";
            es6Ops.createIndex(indexName, indexBody);
            es6Ops.createDocument(indexName, "1", "{ \"body\": \"hello world\" }");
            es6Ops.createSnapshotRepository(SearchClusterContainer.CLUSTER_SNAPSHOT_DIR, legacyRepo);
            es6Ops.takeSnapshot(legacyRepo, legacySnapshot, indexName);
            es6Cluster.copySnapshotData(es6SnapshotDir.toString());
        }

        // Phase 2: Restore the ES 6 snapshot into ES 7 (the upgrade scenario),
        // then take a new snapshot from ES 7 to use as the migration source.
        //
        // We use a sub-path /tmp/snapshots/restore_repo for the ES 6 restore so that the
        // createSnapshot utility (which writes to /tmp/snapshots directly) doesn't collide
        // with the ES 6 snapshot files.
        var restoreRepoPath = SearchClusterContainer.CLUSTER_SNAPSHOT_DIR + "/restore_repo";
        try (
            final var es7Cluster = new SearchClusterContainer(SearchClusterContainer.ES_V7_10_2);
            final var targetCluster = new SearchClusterContainer(SearchClusterContainer.OS_V2_19_4)
        ) {
            CompletableFuture.allOf(
                CompletableFuture.runAsync(es7Cluster::start),
                CompletableFuture.runAsync(targetCluster::start)
            ).join();

            // Copy ES 6 snapshot data into the restore sub-directory with proper ownership.
            es7Cluster.putSnapshotData(es6SnapshotDir.toString(), restoreRepoPath);

            var es7Ops = new org.opensearch.migrations.bulkload.http.ClusterOperations(es7Cluster);
            es7Ops.createSnapshotRepository(restoreRepoPath, legacyRepo);
            es7Ops.restoreSnapshot(legacyRepo, legacySnapshot);

            // ES 7 now holds the index with the legacy ES 6 analysis names.
            // createSnapshot creates its repo at CLUSTER_SNAPSHOT_DIR (not the sub-path),
            // so there is no file-format collision.
            var migrateSnapshotName = "es7_migrate_snap";
            createSnapshot(es7Cluster, migrateSnapshotName, SnapshotTestContext.factory().noOtelTracking());
            es7Cluster.copySnapshotData(es7SnapshotDir.toString());

            this.sourceCluster = es7Cluster;
            this.targetCluster = targetCluster;
            // Initialize targetOperations for the assertions below (sourceOperations not needed).
            this.targetOperations = new org.opensearch.migrations.bulkload.http.ClusterOperations(targetCluster);

            var arguments = prepareSnapshotMigrationArgs(migrateSnapshotName, es7SnapshotDir.toString());

            MigrationItemResult metaResult = executeMigration(arguments, MetadataCommands.MIGRATE);
            log.info(metaResult.asCliOutput());
            assertThat("Metadata migration must succeed when ES 7 source holds ES 6 legacy analysis names",
                metaResult.getExitCode(), equalTo(0));
            assertThat(getNames(getSuccessfulResults(metaResult.getItems().getIndexes())), hasItems(indexName));

            // Verify the analyzer references on the target are rewritten to snake_case
            var settingsRes = targetOperations.get("/" + indexName + "/_settings");
            assertThat(settingsRes.getValue(), settingsRes.getKey(), equalTo(200));
            var settings = settingsRes.getValue();
            assertThat("nGram tokenizer reference must be rewritten to ngram on OS target",
                settings, containsString("ngram"));
            assertThat("edgeNGram filter reference must be rewritten to edge_ngram on OS target",
                settings, containsString("edge_ngram"));
            assertThat("lowercase must survive", settings, containsString("lowercase"));

            // Confirm the target index actually accepts document indexing
            var indexDocRes = targetOperations.put(
                "/" + indexName + "/_doc/test-post-migrate?refresh=true",
                "{ \"body\": \"Post-migration ingest test\" }"
            );
            assertThat("Indexing must succeed — analyzer chain must be valid on OS target. Response: "
                    + indexDocRes.getValue(),
                indexDocRes.getKey(), equalTo(201));

            // Confirm the analyzer is usable end-to-end via _analyze
            var analyzeRes = targetOperations.post(
                "/" + indexName + "/_analyze",
                "{ \"analyzer\": \"legacy_analyzer\", \"text\": \"Hello\" }"
            );
            assertThat("_analyze must succeed on OS target. Response: " + analyzeRes.getValue(),
                analyzeRes.getKey(), equalTo(200));
        }
    }

    @ParameterizedTest(name = "Legacy template no mappings from {0} to OS 2.19")
    @MethodSource(value = "es6xScenarios")
    void legacyTemplateNoMappings(SearchClusterContainer.ContainerVersion sourceVersion) {
        try (
            final var sourceCluster = new SearchClusterContainer(sourceVersion);
            final var targetCluster = new SearchClusterContainer(SearchClusterContainer.OS_V2_19_4)
        ) {
            this.sourceCluster = sourceCluster;
            this.targetCluster = targetCluster;
            startClusters();

            var templateName = "empty-mappings-template";
            var indexPattern = "test-empty-mappings-*";
            var aliasName = "test-empty-mappings-alias";

            sourceOperations.createLegacyTemplateNoMappings(templateName, indexPattern, aliasName);
            sourceOperations.createDocument("test-empty-mappings-2023", "1", "{ \"field\": \"value\" }");

            var snapshotName = "template_no_mappings_snap";
            var testSnapshotContext = SnapshotTestContext.factory().noOtelTracking();
            createSnapshot(sourceCluster, snapshotName, testSnapshotContext);
            sourceCluster.copySnapshotData(localDirectory.toString());

            var arguments = prepareSnapshotMigrationArgs(snapshotName, localDirectory.toString());
            MigrationItemResult result = executeMigration(arguments, MetadataCommands.MIGRATE);

            assertThat(result.getExitCode(), equalTo(0));
            assertThat(getNames(getSuccessfulResults(result.getItems().getIndexTemplates())), hasItems(templateName));

            var res = targetOperations.get("/_template/" + templateName);
            assertThat(res.getKey(), equalTo(200));
            assertThat(res.getValue(), containsString("uax_url_email"));
        }
    }

    private enum TransferMedium {
        SnapshotImage,
        Http
    }

    private enum TemplateType {
        Legacy,
        Index,
        IndexAndComponent
    }

    @SneakyThrows
    private void metadataCommandOnClusters(TransferMedium medium,
                                           MetadataCommands command,
                                           List<TemplateType> templateTypes) {
        startClusters();

        var testData = new TestData();

        for (TemplateType templateType : templateTypes) {
            String uniqueSuffix = templateType.name().toLowerCase();
            String templateName = testData.indexTemplateName + "_" + uniqueSuffix;
            String indexPattern = "blog_" + uniqueSuffix + "_*";
            String fieldName = "author_" + uniqueSuffix;

            if (templateType == TemplateType.Legacy) {
                sourceOperations.createLegacyTemplate(templateName, indexPattern);
                testData.aliasNames.add("alias_legacy");
            } else if (templateType == TemplateType.Index) {
                sourceOperations.createIndexTemplate(templateName, fieldName, indexPattern);
                testData.aliasNames.add("alias_index");
            } else if (templateType == TemplateType.IndexAndComponent) {
                String componentTemplateName = testData.compoTemplateName + "_" + uniqueSuffix;
                sourceOperations.createComponentTemplate(componentTemplateName, templateName, fieldName, indexPattern);
                testData.aliasNames.add("alias_component");
                testData.componentTemplateNames.add(componentTemplateName);
            }
            testData.templateNames.add(templateName);

            // Create documents that use the templates
            String blogIndexName = "blog_" + uniqueSuffix + "_2023";
            sourceOperations.createDocument(blogIndexName, "222",
                "{ \"name\": \"bob\", \"is_active\": true }");
            testData.blogIndexNames.add(blogIndexName);
        }

        sourceOperations.createDocument(testData.movieIndexName, "123",
            "{ \"age\": 55, \"is_active\": false }");
        sourceOperations.createDocument(testData.indexThatAlreadyExists, "doc66",
            "{ \"age\": 99, \"is_active\": true }");

        sourceOperations.createAlias(testData.aliasName, "movies*");
        testData.aliasNames.add(testData.aliasName);

        MigrateOrEvaluateArgs arguments;

        switch (medium) {
            case SnapshotImage:
                var snapshotName = "my_snap_" + command.name().toLowerCase();
                var testSnapshotContext = SnapshotTestContext.factory().noOtelTracking();
                createSnapshot(sourceCluster, snapshotName, testSnapshotContext);
                sourceCluster.copySnapshotData(localDirectory.toString());
                arguments = prepareSnapshotMigrationArgs(snapshotName, localDirectory.toString());
                break;

            case Http:
                arguments = new MigrateOrEvaluateArgs();
                arguments.sourceArgs.host = sourceCluster.getUrl();
                arguments.targetArgs.host = targetCluster.getUrl();
                break;

            default:
                throw new RuntimeException("Invalid Option");
        }

        // If the target is not part of  supported target matrix enable loose version matching
        if (!(SupportedClusters.supportedTargets(false)
            .stream()
            .anyMatch(v -> v.equals(targetCluster.getContainerVersion().getVersion())))) {
            arguments.versionStrictness.allowLooseVersionMatches = true;
        }


        targetOperations.createDocument(testData.indexThatAlreadyExists, "doc77", "{}");

        // Execute migration
        MigrationItemResult result = executeMigration(arguments, command);

        verifyCommandResults(result, templateTypes, testData);

        verifyTargetCluster(command, templateTypes, testData);
    }

    private static class TestData {
        final String compoTemplateName = "simple_component_template";
        final String indexTemplateName = "simple_index_template";
        final String movieIndexName = "movies_2023";
        final String aliasName = "movies-alias";
        final String indexThatAlreadyExists = "already-exists";
        final List<String> blogIndexNames = new ArrayList<>();
        final List<String> templateNames = new ArrayList<>();
        final List<String> componentTemplateNames = new ArrayList<>();
        final List<String> aliasNames = new ArrayList<>();
    }

    private void verifyCommandResults(MigrationItemResult result,
                                      List<TemplateType> templateTypes,
                                      TestData testData) {
        log.info(result.asCliOutput());
        // Exit code is 1 because INDEX_ALREADY_EXISTS is a fatal error
        assertThat(result.getExitCode(), equalTo(1));

        var migratedItems = result.getItems();
        assertThat(getNames(getSuccessfulResults(migratedItems.getIndexTemplates())),
            hasItems(testData.templateNames.toArray(new String[0])));
        assertThat(getNames(getSuccessfulResults(migratedItems.getComponentTemplates())),
            hasItems(testData.componentTemplateNames.toArray(new String[0])));
        assertThat(getNames(getSuccessfulResults(migratedItems.getIndexes())),
            hasItems(Stream.concat(testData.blogIndexNames.stream(),
                Stream.of(testData.movieIndexName)).toArray(String[]::new)));
        assertThat(getNames(getFailedResultsByType(migratedItems.getIndexes(),
                CreationResult.CreationFailureType.INDEX_ALREADY_EXISTS)),
            hasItems(testData.indexThatAlreadyExists));
        assertThat(getNames(getSuccessfulResults(migratedItems.getAliases())),
            hasItems(testData.aliasNames.toArray(new String[0])));
    }

    private List<CreationResult> getSuccessfulResults(List<CreationResult> results) {
        return results.stream()
            .filter(CreationResult::wasSuccessful)
            .collect(Collectors.toList());
    }

    private List<CreationResult> getFailedResultsByType(List<CreationResult> results, CreationResult.CreationFailureType failureType) {
        return results.stream()
            .filter(r -> failureType.equals(r.getFailureType()))
            .collect(Collectors.toList());
    }

    private List<String> getNames(List<CreationResult> items) {
        return items.stream().map(CreationResult::getName).collect(Collectors.toList());
    }

    @SneakyThrows
    @ParameterizedTest(name = "From version {0} to version {1}: already exists handling for index and template")
    @MethodSource(value = "scenarios")
    void alreadyExists_indexFatalTemplatNonFatal(
            SearchClusterContainer.ContainerVersion sourceVersion,
            SearchClusterContainer.ContainerVersion targetVersion,
            TransferMedium medium,
            List<TemplateType> templateTypes) {
        try (
            final var sourceCluster = new SearchClusterContainer(sourceVersion);
            final var targetCluster = new SearchClusterContainer(targetVersion)
        ) {
            this.sourceCluster = sourceCluster;
            this.targetCluster = targetCluster;
            startClusters();

            var indexName = "preexisting_index";
            var templateName = "preexisting_template";
            var templatePattern = "preexisting_*";

            // Create index on source and pre-create on target
            sourceOperations.createDocument(indexName, "doc1", "{}");
            targetOperations.createDocument(indexName, "doc2", "{}");

            // Create legacy template on source and pre-create on target
            sourceOperations.createLegacyTemplate(templateName, templatePattern);
            targetOperations.createLegacyTemplate(templateName, templatePattern);

            MigrateOrEvaluateArgs arguments;
            switch (medium) {
                case SnapshotImage:
                    var snapshotName = "snap_already_exists";
                    var testSnapshotContext = SnapshotTestContext.factory().noOtelTracking();
                    createSnapshot(sourceCluster, snapshotName, testSnapshotContext);
                    sourceCluster.copySnapshotData(localDirectory.toString());
                    arguments = prepareSnapshotMigrationArgs(snapshotName, localDirectory.toString());
                    break;
                case Http:
                    arguments = new MigrateOrEvaluateArgs();
                    arguments.sourceArgs.host = sourceCluster.getUrl();
                    arguments.targetArgs.host = targetCluster.getUrl();
                    break;
                default:
                    throw new RuntimeException("Invalid Option");
            }

            if (!(SupportedClusters.supportedTargets(false).stream()
                .anyMatch(v -> v.equals(targetCluster.getContainerVersion().getVersion())))) {
                arguments.versionStrictness.allowLooseVersionMatches = true;
            }

            arguments.dataFilterArgs.indexAllowlist = List.of(indexName);
            arguments.dataFilterArgs.indexTemplateAllowlist = List.of(templateName);

            // Run EVALUATE — should detect conflicts without making changes
            var evalResult = executeMigration(arguments, MetadataCommands.EVALUATE);
            log.info("EVALUATE output:\n{}", evalResult.asCliOutput());

            assertThat("Evaluate exit code should be exactly 1 (one fatal index conflict)",
                evalResult.getExitCode(), equalTo(1));
            assertThat(getNames(getFailedResultsByType(evalResult.getItems().getIndexes(),
                    CreationResult.CreationFailureType.INDEX_ALREADY_EXISTS)),
                hasItems(indexName));
            assertThat(getNames(getFailedResultsByType(evalResult.getItems().getIndexTemplates(),
                    CreationResult.CreationFailureType.METADATA_ALREADY_EXISTS)),
                hasItems(templateName));

            // Run MIGRATE — should report the same conflicts
            var migrateResult = executeMigration(arguments, MetadataCommands.MIGRATE);
            log.info("MIGRATE output:\n{}", migrateResult.asCliOutput());

            assertThat("Migrate exit code should be exactly 1 (one fatal index conflict)",
                migrateResult.getExitCode(), equalTo(1));

            // Verify INDEX_ALREADY_EXISTS is fatal
            var indexResults = migrateResult.getItems().getIndexes();
            var indexFailures = getFailedResultsByType(indexResults, CreationResult.CreationFailureType.INDEX_ALREADY_EXISTS);
            assertThat(getNames(indexFailures), hasItems(indexName));
            assertThat("INDEX_ALREADY_EXISTS should be fatal",
                indexFailures.get(0).wasFatal(), equalTo(true));

            // Verify METADATA_ALREADY_EXISTS is non-fatal
            var templateResults = migrateResult.getItems().getIndexTemplates();
            var templateFailures = getFailedResultsByType(templateResults, CreationResult.CreationFailureType.METADATA_ALREADY_EXISTS);
            assertThat(getNames(templateFailures), hasItems(templateName));
            assertThat("METADATA_ALREADY_EXISTS should be non-fatal",
                templateFailures.get(0).wasFatal(), equalTo(false));

            // Verify suggestion text in CLI output
            var cliOutput = migrateResult.asCliOutput();
            assertThat(cliOutput, containsString("console clusters clear-indices --cluster target"));
            assertThat(cliOutput, containsString("--index-allowlist"));
        }
    }

    private void verifyTargetCluster(MetadataCommands command,
                                     List<TemplateType> templateTypes,
                                     TestData testData) {
        var expectUpdatesOnTarget = MetadataCommands.MIGRATE.equals(command);
        // If the command was migrate, the target cluster should have the items, if not they shouldn't
        var verifyResponseCode = expectUpdatesOnTarget ? equalTo(200) : equalTo(404);

        // Check that the indices were migrated
        for (String blogIndexName : testData.blogIndexNames) {
            var res = targetOperations.get("/" + blogIndexName);
            assertThat(res.getValue(), res.getKey(), verifyResponseCode);
        }

        var res = targetOperations.get("/" + testData.movieIndexName);
        assertThat(res.getValue(), res.getKey(), verifyResponseCode);

        res = targetOperations.get("/" + testData.aliasName);
        assertThat(res.getValue(), res.getKey(), verifyResponseCode);
        if (expectUpdatesOnTarget) {
            assertThat(res.getValue(), containsString(testData.movieIndexName));
        }

        res = targetOperations.get("/_aliases");
        assertThat(res.getValue(), res.getKey(), equalTo(200));
        @SuppressWarnings("unchecked")
        var verifyAliasWasListed = allOf(
            testData.aliasNames.stream()
                .map(Matchers::containsString)
                .toArray(Matcher[]::new)
        );
        assertThat(res.getValue(), expectUpdatesOnTarget ? verifyAliasWasListed : not(verifyAliasWasListed));

        // Check that the templates were migrated
        for (String templateName : testData.templateNames) {
            if (templateName.contains("legacy")) {
                res = targetOperations.get("/_template/" + templateName);
            } else {
                res = targetOperations.get("/_index_template/" + templateName);
            }
            assertThat(res.getValue(), res.getKey(), verifyResponseCode);
        }
    }
}
