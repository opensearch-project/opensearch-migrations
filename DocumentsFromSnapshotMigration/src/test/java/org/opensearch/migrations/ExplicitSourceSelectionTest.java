package org.opensearch.migrations;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.bulkload.common.DeltaMode;
import org.opensearch.migrations.bulkload.pipeline.provider.EsSnapshotSourceProvider;
import org.opensearch.migrations.bulkload.pipeline.provider.SolrBackupSourceProvider;
import org.opensearch.migrations.bulkload.pipeline.source.DocumentSource;
import org.opensearch.migrations.bulkload.pipeline.spi.CoordinationRequirement;
import org.opensearch.migrations.bulkload.pipeline.spi.DocumentSourceProvider;
import org.opensearch.migrations.bulkload.pipeline.spi.DocumentSourceSpec;
import org.opensearch.migrations.bulkload.pipeline.spi.SourceRuntime;
import org.opensearch.migrations.jcommander.JsonCommandLineParser;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.beust.jcommander.ParametersDelegate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers {@code --source-kind}/{@code --source-config}. Inference must stay untouched when neither
 * is given, so existing invocations keep working.
 */
class ExplicitSourceSelectionTest {

    private static final String SOLR_CONFIG =
        "{\"repoUri\":\"file:///backups\",\"backupName\":\"nightly\",\"solrMajorVersion\":8}";

    private static final String ES_CONFIG =
        "{\"repoUri\":\"file:///snapshots\",\"snapshotName\":\"nightly\",\"version\":\"ES 7.10.2\"}";

    private static final String SOLR_S3_CONFIG =
        "{\"repoUri\":\"s3://bucket/backups\",\"backupName\":\"nightly\",\"solrMajorVersion\":8,"
            + "\"s3Region\":\"us-east-1\"}";

    /** {@link #ES_CONFIG} quoted, for embedding in an ---INLINE-JSON document. */
    private static final String ES_CONFIG_AS_JSON_STRING =
        "\"" + ES_CONFIG.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";

    private static final String COORDINATOR = "http://coordinator:9200";

    /** The stub provider ignores its config; the checks under test never read it. */
    private static final JsonNode STUB_CONFIG = JsonNodeFactory.instance.objectNode();

    private static RfsMigrateDocuments.Args argsWithExplicitSource(String kind, String config) {
        var args = new RfsMigrateDocuments.Args();
        args.sourceKind = kind;
        args.sourceConfig = config;
        return args;
    }

    @Test
    void explicitKindWinsOverInference() {
        var args = argsWithExplicitSource(SolrBackupSourceProvider.KIND, SOLR_CONFIG);
        // A version that inference would read as an ES snapshot.
        args.legacySource.sourceVersion = null;

        var selection = RfsMigrateDocuments.selectSource(args, false);

        assertThat(selection.kind(), equalTo(SolrBackupSourceProvider.KIND));
        assertThat(selection.config().get("backupName").asText(), equalTo("nightly"));
    }

    @Test
    void inferenceIsUnchangedWhenNeitherArgumentIsGiven() {
        var args = new RfsMigrateDocuments.Args();
        args.legacySource.sourceVersion = Version.fromString("ES 7.10.2");
        args.legacySource.repoUri = "file:///snapshots";
        args.legacySource.snapshotName = "nightly";

        var selection = RfsMigrateDocuments.selectSource(args, false);

        assertThat(selection.kind(), equalTo(EsSnapshotSourceProvider.KIND));
    }

    @Test
    void sourceConfigReadsInlineJson() {
        var config = RfsMigrateDocuments.readSourceConfig(SOLR_CONFIG);

        assertThat(config.get("repoUri").asText(), equalTo("file:///backups"));
    }

    @Test
    void sourceConfigReadsAFileWhenPrefixedWithAt(@TempDir Path tempDir) throws Exception {
        var file = tempDir.resolve("source.json");
        Files.writeString(file, SOLR_CONFIG);

        var config = RfsMigrateDocuments.readSourceConfig("@" + file);

        assertThat(config.get("backupName").asText(), equalTo("nightly"));
    }

    @Test
    void sourceConfigReportsUnreadableJson() {
        assertThat(assertThrows(ParameterException.class,
            () -> RfsMigrateDocuments.readSourceConfig("not json")).getMessage(),
            containsString("--source-config"));
    }

    @Test
    void sourceConfigReportsAMissingFile(@TempDir Path tempDir) {
        var missing = tempDir.resolve("absent.json");

        assertThat(assertThrows(ParameterException.class,
            () -> RfsMigrateDocuments.readSourceConfig("@" + missing)).getMessage(),
            containsString("--source-config"));
    }

    @Test
    void eitherArgumentWithoutTheOtherIsRejected() {
        assertThat(assertThrows(ParameterException.class, () -> RfsMigrateDocuments.validateSourceSelection(
            argsWithExplicitSource(SolrBackupSourceProvider.KIND, null))).getMessage(),
            containsString("must be given together"));
        assertThat(assertThrows(ParameterException.class, () -> RfsMigrateDocuments.validateSourceSelection(
            argsWithExplicitSource(null, SOLR_CONFIG))).getMessage(),
            containsString("must be given together"));
    }

    @Test
    void supersededArgumentsAreRejectedRatherThanIgnored() {
        var args = argsWithExplicitSource(SolrBackupSourceProvider.KIND, SOLR_CONFIG);
        args.legacySource.repoUri = "file:///backups";
        args.legacySource.snapshotName = "nightly";

        var message = assertThrows(ParameterException.class,
            () -> RfsMigrateDocuments.validateSourceSelection(args)).getMessage();

        assertThat(message, containsString("--repo-uri"));
        assertThat(message, containsString("--snapshot-name"));
    }

    @Test
    void anUnknownKindIsRejectedAndListsTheAvailableOnes() {
        var message = assertThrows(ParameterException.class,
            () -> RfsMigrateDocuments.validateSourceSelection(
                argsWithExplicitSource("not-a-kind", SOLR_CONFIG))).getMessage();

        assertThat(message, containsString("not-a-kind"));
        assertThat(message, containsString(EsSnapshotSourceProvider.KIND));
    }

    @Test
    void aKindIsAcceptedRegardlessOfCaseOrSurroundingSpace() {
        assertDoesNotThrow(() -> RfsMigrateDocuments.validateSourceSelection(
            argsWithExplicitSource("  Solr-Backup  ", SOLR_CONFIG)));
    }

    @Test
    void neitherArgumentLeavesTheExistingValidationInPlace() {
        var args = new RfsMigrateDocuments.Args();

        // No source kind, so the per-source checks still run and still complain.
        assertThat(assertThrows(ParameterException.class,
            () -> RfsMigrateDocuments.validateArgs(args)).getMessage(),
            containsString("--snapshot-name"));
    }

    @Test
    void supersededSpecOnlyArgumentsAreRejected() {
        var args = argsWithExplicitSource(EsSnapshotSourceProvider.KIND, ES_CONFIG);
        args.legacySource.experimentalDeltaMode = DeltaMode.UPDATES_ONLY;
        args.legacySource.useRecoverySource = true;
        args.legacySource.maxShardSizeBytes = 1024L;

        var message = assertThrows(ParameterException.class,
            () -> RfsMigrateDocuments.validateSourceSelection(args)).getMessage();

        assertThat(message, containsString("--experimental-delta-mode"));
        assertThat(message, containsString("--use-recovery-source"));
        assertThat(message, containsString("--max-shard-size-bytes"));
    }

    @Test
    void aSupersededArgumentNestedInADelegateIsRejected() {
        var args = argsWithExplicitSource(EsSnapshotSourceProvider.KIND, ES_CONFIG);
        args.legacySource.versionStrictness.allowLooseVersionMatches = true;

        assertThat(assertThrows(ParameterException.class,
            () -> RfsMigrateDocuments.validateSourceSelection(args)).getMessage(),
            containsString(VersionStrictness.ALLOW_LOOSE_VERSION_MATCHING_PARAM_KEY));
    }

    @Test
    void aSupersededArgumentGivenItsOwnDefaultValueIsStillRejected() {
        var args = argsWithExplicitSource(EsSnapshotSourceProvider.KIND, ES_CONFIG);
        args.legacySource.maxShardSizeBytes = RfsMigrateDocuments.DEFAULT_MAX_SHARD_SIZE_BYTES;

        assertThat(assertThrows(ParameterException.class,
            () -> RfsMigrateDocuments.validateSourceSelection(args)).getMessage(),
            containsString("--max-shard-size-bytes"));
    }

    @Test
    void aSupersededToggleGivenAsFalseThroughJsonIsStillRejected() {
        var args = new RfsMigrateDocuments.Args();
        // An arity-0 flag cannot be false on the command line, but JSON can say so.
        JsonCommandLineParser.newBuilder().addObject(args).build()
            .parse(new String[]{"---INLINE-JSON", "{\"sourceKind\": \"" + EsSnapshotSourceProvider.KIND
                + "\", \"sourceConfig\": " + ES_CONFIG_AS_JSON_STRING
                + ", \"useRecoverySource\": false, \"allowLooseVersionMatching\": false}"});

        assertThat(args.legacySource.useRecoverySource, equalTo(false));

        var message = assertThrows(ParameterException.class,
            () -> RfsMigrateDocuments.validateSourceSelection(args)).getMessage();
        assertThat(message, containsString("--use-recovery-source"));
        assertThat(message, containsString(VersionStrictness.ALLOW_LOOSE_VERSION_MATCHING_PARAM_KEY));
    }

    /** Every parameter in the group, nested delegates included, must be reported once it is set. */
    @Test
    void everyParameterInTheSupersededGroupIsReported() throws Exception {
        var parameterCount = parametersIn(new RfsMigrateDocuments.LegacySourceArgs()).size();
        assertThat(parameterCount == 0, equalTo(false));

        for (int i = 0; i < parameterCount; i++) {
            // A fresh group each time, so the message has one flag to name.
            var args = argsWithExplicitSource(EsSnapshotSourceProvider.KIND, ES_CONFIG);
            var parameter = parametersIn(args.legacySource).get(i);
            var field = parameter.getValue();

            // A defaulted field reads as "not given" when the caller passes that same value.
            assertThat("default of " + field.getName() + " must be null, see LegacySourceArgs",
                field.get(parameter.getKey()), nullValue());

            field.set(parameter.getKey(), nonDefaultValueFor(field));

            var flag = field.getAnnotation(Parameter.class).names()[0];
            assertThat(flag, assertThrows(ParameterException.class,
                () -> RfsMigrateDocuments.validateSourceSelection(args)).getMessage(),
                containsString(flag));
        }
    }

    /** Every {@code @Parameter} the group holds, paired with the object that owns it. */
    private static List<Map.Entry<Object, Field>> parametersIn(Object group) throws IllegalAccessException {
        var found = new ArrayList<Map.Entry<Object, Field>>();
        for (var field : group.getClass().getDeclaredFields()) {
            if (field.isSynthetic()) {
                continue;
            }
            field.setAccessible(true);
            if (field.isAnnotationPresent(ParametersDelegate.class)) {
                found.addAll(parametersIn(field.get(group)));
            } else if (field.isAnnotationPresent(Parameter.class)) {
                found.add(Map.entry(group, field));
            }
        }
        return found;
    }

    private static Object nonDefaultValueFor(Field field) {
        var type = field.getType();
        if (type == String.class) {
            return "given-by-this-test";
        }
        if (type == long.class || type == Long.class) {
            return 4096L;
        }
        if (type == boolean.class || type == Boolean.class) {
            return true;
        }
        if (type == Version.class) {
            return Version.fromString("ES 7.10.2");
        }
        if (type.isEnum()) {
            return type.getEnumConstants()[0];
        }
        throw new AssertionError("No non-default value known for " + field.getName() + " of type " + type
            + "; teach this test about the type so the new flag stays covered.");
    }

    @Test
    void workingDirectoriesAreStillRequiredUnderExplicitSelection() {
        var args = argsWithExplicitSource(EsSnapshotSourceProvider.KIND, ES_CONFIG);

        assertThat(assertThrows(ParameterException.class,
            () -> RfsMigrateDocuments.validateArgs(args)).getMessage(),
            containsString("--lucene-dir"));
    }

    @Test
    void aSolrBackupStillNeedsACoordinatorHost() {
        var args = argsWithExplicitSource(SolrBackupSourceProvider.KIND, SOLR_CONFIG);
        args.luceneDir = "/tmp/lucene";

        assertThat(assertThrows(ParameterException.class,
            () -> RfsMigrateDocuments.validateArgs(args)).getMessage(),
            containsString("--coordinator-host"));
    }

    @Test
    void aSolrBackupDoesNotNeedAWorkingDirectory() {
        var args = argsWithExplicitSource(SolrBackupSourceProvider.KIND, SOLR_CONFIG);
        args.coordinatorArgs.host = "http://coordinator:9200";

        // Reads the backup in place, matching the inferred Solr path.
        assertDoesNotThrow(() -> RfsMigrateDocuments.validateArgs(args));
    }

    @Test
    void eitherWorkingDirectorySatisfiesASnapshot() {
        var withLocalDir = argsWithExplicitSource(EsSnapshotSourceProvider.KIND, ES_CONFIG);
        withLocalDir.localDir = "/tmp/local";

        assertDoesNotThrow(() -> RfsMigrateDocuments.validateArgs(withLocalDir));
    }

    /**
     * The runtime checks read what a provider declares, not what class it is. A provider the CLI has
     * never heard of gets held to the same rules, which is the point of moving these off an
     * {@code instanceof}.
     */
    private record StubSpec() implements DocumentSourceSpec {
        @Override
        public String kind() {
            return "stub-source";
        }
    }

    private record StubProvider(CoordinationRequirement coordination, boolean needsScratch, boolean needsWorkingDir)
        implements DocumentSourceProvider<StubSpec> {

        @Override
        public String kind() {
            return "stub-source";
        }

        @Override
        public StubSpec parseSpec(JsonNode config) {
            return new StubSpec();
        }

        @Override
        public CoordinationRequirement coordinationRequirement() {
            return coordination;
        }

        @Override
        public boolean requiresScratchDirectory(StubSpec spec) {
            return needsScratch;
        }

        @Override
        public boolean requiresWorkingDirectory(StubSpec spec) {
            return needsWorkingDir;
        }

        @Override
        public DocumentSource create(StubSpec spec, SourceRuntime runtime) {
            throw new UnsupportedOperationException("not needed; runtime checks never construct");
        }
    }

    @Test
    void anyProviderDeclaringExternalCoordinationNeedsACoordinatorHost() {
        var provider = new StubProvider(CoordinationRequirement.EXTERNAL_REQUIRED, false, false);

        var message = assertThrows(ParameterException.class,
            () -> RfsMigrateDocuments.validateRuntimeArgs(new RfsMigrateDocuments.Args(), provider, STUB_CONFIG)).getMessage();

        assertThat(message, containsString("--coordinator-host"));
        assertThat(message, containsString("stub-source"));
    }

    @Test
    void aProviderAllowingTargetCoordinationNeedsNoCoordinatorHost() {
        var args = new RfsMigrateDocuments.Args();
        args.luceneDir = "/tmp/lucene";

        assertDoesNotThrow(() -> RfsMigrateDocuments.validateRuntimeArgs(args,
            new StubProvider(CoordinationRequirement.TARGET_ALLOWED, false, true), STUB_CONFIG));
    }

    @Test
    void aProviderDeclaringNoWorkingDirectoryIsNotAskedForOne() {
        assertDoesNotThrow(() -> RfsMigrateDocuments.validateRuntimeArgs(new RfsMigrateDocuments.Args(),
            new StubProvider(CoordinationRequirement.TARGET_ALLOWED, false, false), STUB_CONFIG));
    }

    @Test
    void theTwoRuntimeRequirementsAreCheckedIndependently() {
        var provider = new StubProvider(CoordinationRequirement.EXTERNAL_REQUIRED, false, true);
        var args = new RfsMigrateDocuments.Args();
        args.coordinatorArgs.host = "http://coordinator:9200";

        // Coordination satisfied, working directory still missing.
        assertThat(assertThrows(ParameterException.class,
            () -> RfsMigrateDocuments.validateRuntimeArgs(args, provider, STUB_CONFIG)).getMessage(),
            containsString("--lucene-dir"));
    }

    @Test
    void aLocalSolrBackupNeedsNeitherDirectory() {
        var args = argsWithExplicitSource(SolrBackupSourceProvider.KIND, SOLR_CONFIG);
        args.coordinatorArgs.host = COORDINATOR;

        // file:// is read where it sits, so nothing is downloaded and nothing is unpacked.
        assertDoesNotThrow(() -> RfsMigrateDocuments.validateArgs(args));
    }

    @Test
    void anS3SolrBackupIsRejectedWithoutALocalDir() {
        var args = argsWithExplicitSource(SolrBackupSourceProvider.KIND, SOLR_S3_CONFIG);
        args.coordinatorArgs.host = COORDINATOR;

        // Without this the backup would download into java.io.tmpdir; the legacy path rejects it too.
        assertThat(assertThrows(ParameterException.class,
            () -> RfsMigrateDocuments.validateArgs(args)).getMessage(),
            containsString("--local-dir"));
    }

    @Test
    void anS3SolrBackupIsAcceptedWithALocalDir() {
        var args = argsWithExplicitSource(SolrBackupSourceProvider.KIND, SOLR_S3_CONFIG);
        args.coordinatorArgs.host = COORDINATOR;
        args.localDir = "/tmp/local";

        assertDoesNotThrow(() -> RfsMigrateDocuments.validateArgs(args));
    }

    @Test
    void anS3SolrBackupIsNotSatisfiedByALuceneDirAlone() {
        var args = argsWithExplicitSource(SolrBackupSourceProvider.KIND, SOLR_S3_CONFIG);
        args.coordinatorArgs.host = COORDINATOR;
        args.luceneDir = "/tmp/lucene";

        // scratchDir falls back to luceneDir, but downloads belong in the directory meant for them.
        assertThat(assertThrows(ParameterException.class,
            () -> RfsMigrateDocuments.validateArgs(args)).getMessage(),
            containsString("--local-dir"));
    }

    @Test
    void sourceConfigIsReportedRatherThanNullPointing() {
        assertThat(assertThrows(ParameterException.class,
            () -> RfsMigrateDocuments.readSourceConfig(null)).getMessage(),
            containsString("--source-config"));
    }
}
