package org.opensearch.migrations;

import java.util.List;
import java.util.Map;

import org.opensearch.migrations.bulkload.pipeline.provider.EsSnapshotSourceProvider;
import org.opensearch.migrations.bulkload.pipeline.provider.FailedDocumentStreamSourceProvider;
import org.opensearch.migrations.bulkload.pipeline.provider.FailedDocumentStreamSourceSpec;
import org.opensearch.migrations.bulkload.pipeline.spi.DocumentSourceRegistry;
import org.opensearch.migrations.reindexer.faileddocumentstream.source.FailedDocumentStreamRecordTransformer;
import org.opensearch.migrations.transform.IJsonTransformer;

import com.beust.jcommander.ParameterException;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The command-line side of a redrive: the mandatory transform is chained in, and the two things a
 * source cannot check for itself are rejected before anything starts.
 */
class RedriveSelectionTest {

    private static final String STREAM_URI = "s3://failure-bucket/rfs-failed-document-stream";
    private static final String SESSION = "session-1";

    private static FailedDocumentStreamSourceSpec spec() {
        return new FailedDocumentStreamSourceSpec(
            STREAM_URI, SESSION, "us-east-1", null, List.of(), List.of());
    }

    private static RfsMigrateDocuments.Args redriveArgs() {
        var args = new RfsMigrateDocuments.Args();
        args.sourceKind = FailedDocumentStreamSourceProvider.KIND;
        args.sourceConfig = spec().toJson().toString();
        args.indexNameSuffix = "redrive-1";
        return args;
    }

    private static void validateRedrive(RfsMigrateDocuments.Args args) {
        var provider = DocumentSourceRegistry.getDefault().resolve(args.sourceKind);
        RfsMigrateDocuments.validateRedriveArgs(
            args, provider, RfsMigrateDocuments.readSourceConfig(args.sourceConfig));
    }

    @Test
    void theRegistryServesTheRedriveKind() {
        assertThat(DocumentSourceRegistry.getDefault().resolve(FailedDocumentStreamSourceProvider.KIND),
            instanceOf(FailedDocumentStreamSourceProvider.class));
    }

    @Test
    void chainsTheSourcesMandatoryTransformAheadOfTheUsers() {
        var provider = DocumentSourceRegistry.getDefault().resolve(FailedDocumentStreamSourceProvider.KIND);
        var userTransformerRan = new java.util.concurrent.atomic.AtomicBoolean();
        IJsonTransformer user = input -> {
            userTransformerRan.set(true);
            // Records are already operations by now.
            @SuppressWarnings("unchecked")
            var operations = (List<Map<String, Object>>) input;
            assertThat(operations, hasSize(1));
            assertThat(operations.get(0).containsKey("operation"), equalTo(true));
            return input;
        };

        var chained = RfsMigrateDocuments.chainRequiredPreTransform(
            provider, spec().toJson(), () -> user).get();

        var record = org.opensearch.migrations.reindexer.faileddocumentstream.source
            .FailedDocumentStreamFixtures.record("orders", "doc-1",
                org.opensearch.migrations.reindexer.faileddocumentstream.FailureClass.NON_RETRYABLE);
        chained.transformJson(List.of(Map.of(
            "document", asMap(record),
            "operation", Map.of("_index", "orders"))));

        assertThat("the user's transform still runs, after the record became an operation",
            userTransformerRan.get(), equalTo(true));
    }

    @Test
    void suppliesTheMandatoryTransformOnItsOwnWhenTheRunConfiguredNone() {
        var provider = DocumentSourceRegistry.getDefault().resolve(FailedDocumentStreamSourceProvider.KIND);

        var chained = RfsMigrateDocuments.chainRequiredPreTransform(provider, spec().toJson(), null);

        assertThat(chained.get(), instanceOf(FailedDocumentStreamRecordTransformer.class));
    }

    @Test
    void leavesASourceThatDeclaresNoTransformAlone() {
        var provider = DocumentSourceRegistry.getDefault().resolve(EsSnapshotSourceProvider.KIND);
        java.util.function.Supplier<IJsonTransformer> user = () -> input -> input;
        var config = RfsMigrateDocuments.readSourceConfig(
            "{\"repoUri\":\"file:///snapshots\",\"snapshotName\":\"nightly\"}");

        assertThat(RfsMigrateDocuments.chainRequiredPreTransform(provider, config, user), sameInstance(user));
        assertThat(RfsMigrateDocuments.chainRequiredPreTransform(provider, config, null), equalTo(null));
    }

    @Test
    void rejectsARedriveThatWouldReadItsOwnOutput() {
        var args = redriveArgs();
        // Point this run's stream at the session it is redriving.
        args.failedDocumentStreamArgs.failedDocumentStreamS3Bucket = "failure-bucket";
        args.failedDocumentStreamArgs.failedDocumentStreamS3Prefix = "rfs-failed-document-stream/";
        args.failedDocumentStreamArgs.failedDocumentStreamSessionId = SESSION;

        var thrown = assertThrows(ParameterException.class, () -> validateRedrive(args));

        assertThat(thrown.getMessage(), containsString("would never finish"));
    }

    @Test
    void allowsARedriveThatWritesToADifferentSession() {
        var args = redriveArgs();
        args.failedDocumentStreamArgs.failedDocumentStreamS3Bucket = "failure-bucket";
        args.failedDocumentStreamArgs.failedDocumentStreamS3Prefix = "rfs-failed-document-stream/";
        args.failedDocumentStreamArgs.failedDocumentStreamSessionId = "redrive-of-" + SESSION;

        assertDoesNotThrow(() -> validateRedrive(args));
    }

    @Test
    void allowsARedriveThatWritesToADifferentBucket() {
        var args = redriveArgs();
        args.failedDocumentStreamArgs.failedDocumentStreamS3Bucket = "another-bucket";
        args.failedDocumentStreamArgs.failedDocumentStreamS3Prefix = "rfs-failed-document-stream/";
        args.failedDocumentStreamArgs.failedDocumentStreamSessionId = SESSION;

        assertDoesNotThrow(() -> validateRedrive(args));
    }

    @Test
    void demandsItsOwnCoordinationNamespace() {
        var args = redriveArgs();
        args.indexNameSuffix = "";

        var thrown = assertThrows(ParameterException.class, () -> validateRedrive(args));

        assertThat(thrown.getMessage(), containsString("--session-name is required"));
    }

    @Test
    void leavesOtherSourcesAlone() {
        // Neither check applies to a snapshot source.
        var args = new RfsMigrateDocuments.Args();
        args.sourceKind = EsSnapshotSourceProvider.KIND;
        args.sourceConfig = "{\"repoUri\":\"file:///snapshots\",\"snapshotName\":\"nightly\"}";
        args.indexNameSuffix = "";

        assertDoesNotThrow(() -> validateRedrive(args));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(String json) {
        try {
            return org.opensearch.migrations.bulkload.common.ObjectMapperFactory.createDefaultMapper()
                .readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
