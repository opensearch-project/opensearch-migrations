package org.opensearch.migrations;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.opensearch.migrations.bulkload.common.CollectionRoutedTarget;
import org.opensearch.migrations.bulkload.common.DeltaMode;
import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.ServerlessCollectionRouting;
import org.opensearch.migrations.bulkload.common.ServerlessCollectionType;
import org.opensearch.migrations.bulkload.pipeline.source.DocumentSource;
import org.opensearch.migrations.bulkload.workcoordination.IWorkCoordinator;
import org.opensearch.migrations.bulkload.worker.WorkItemCursor;
import org.opensearch.migrations.jcommander.JsonCommandLineParser;
import org.opensearch.migrations.reindexer.faileddocumentstream.FailedDocumentStreamSink;
import org.opensearch.migrations.reindexer.tracing.RootDocumentMigrationContext;

import com.beust.jcommander.ParameterException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the failed document stream / coordinator helper methods on {@link RfsMigrateDocuments}.
 * The helpers are package-private specifically so tests in this package can
 * exercise them without spinning up an actual cluster.
 */
class RfsMigrateDocumentsHelpersTest {

    // ---- resolveSessionId -------------------------------------------------

    @Test
    void resolveSessionId_prefersExplicitCliFlag() {
        var args = new RfsMigrateDocuments.Args();
        args.failedDocumentStreamArgs.failedDocumentStreamSessionId = "explicit-session";
        // Even if ARGO_WORKFLOW_UID happens to be set in the env, the explicit
        // CLI flag must win.
        assertThat(RfsMigrateDocuments.resolveSessionId(args, "ignored-worker"),
            equalTo("explicit-session"));
    }

    @Test
    void resolveSessionId_blankCliFlagFallsThrough() {
        var args = new RfsMigrateDocuments.Args();
        args.failedDocumentStreamArgs.failedDocumentStreamSessionId = "   ";  // blank — should be skipped
        // If ARGO_WORKFLOW_UID isn't set in this environment we land on the
        // worker-id fallback. (CI doesn't set it.)
        if (System.getenv("ARGO_WORKFLOW_UID") == null) {
            assertThat(RfsMigrateDocuments.resolveSessionId(args, "node-7"),
                equalTo("worker-node-7"));
        }
    }

    @Test
    void resolveSessionId_workerFallbackWhenNothingElseProvided() {
        var args = new RfsMigrateDocuments.Args();
        // Only assert the fallback when ARGO_WORKFLOW_UID is unset — otherwise
        // the env value (correctly) wins and that's not what we're testing.
        if (System.getenv("ARGO_WORKFLOW_UID") == null) {
            assertThat(RfsMigrateDocuments.resolveSessionId(args, "abc"),
                equalTo("worker-abc"));
        }
    }

    // ---- buildFailedDocumentStreamSink -----------------------------------------------------

    @Test
    void buildFailedDocumentStreamSink_returnsNullWhenNoBucketConfigured() {
        var args = new RfsMigrateDocuments.Args();
        // No --failed-document-stream-s3-bucket: the sink is disabled. The bucket is now explicit
        // configuration only — RFS no longer falls back to the MIGRATIONS_DEFAULT_S3_BUCKET env var,
        // so this holds regardless of the pod environment.
        assertThat(RfsMigrateDocuments.buildFailedDocumentStreamSink(args, "w", "sess"), nullValue());
    }

    @Test
    void buildFailedDocumentStreamSink_buildsSinkWithExplicitBucketAndRegion() {
        var args = new RfsMigrateDocuments.Args();
        args.failedDocumentStreamArgs.failedDocumentStreamS3Bucket = "my-failed-document-stream-bucket";
        args.failedDocumentStreamArgs.failedDocumentStreamS3Region = "us-east-1";
        args.failedDocumentStreamArgs.failedDocumentStreamS3Prefix = "rfs-failed-document-stream/";

        var sink = RfsMigrateDocuments.buildFailedDocumentStreamSink(args, "worker-1", "sess-A");

        assertThat(sink, notNullValue());
        // S3FailedDocumentStreamSink#getLocation embeds bucket, prefix, and session id — using it
        // as a single read-back avoids reflection on the sink's private fields.
        assertThat(sink.getLocation(),
            equalTo("s3://my-failed-document-stream-bucket/rfs-failed-document-stream/session=sess-A/"));
    }

    @Test
    void buildFailedDocumentStreamSink_bucketFromInlineJsonConfigTurnsTheStreamOn() {
        // The orchestrator sends options as ---INLINE-JSON with camelCase keys.
        var args = new RfsMigrateDocuments.Args();
        JsonCommandLineParser.newBuilder().addObject(args).build()
            .parse(new String[]{"---INLINE-JSON",
                "{\"failedDocumentStreamS3Bucket\": \"from-json\", \"failedDocumentStreamS3Region\": \"us-east-1\"}"});

        assertThat(args.failedDocumentStreamArgs.failedDocumentStreamS3Bucket, equalTo("from-json"));
        assertThat(RfsMigrateDocuments.buildFailedDocumentStreamSink(args, "w", "s"), notNullValue());
    }

    @Test
    void buildFailedDocumentStreamSink_inlineJsonWithoutBucketLeavesTheStreamOff() {
        var args = new RfsMigrateDocuments.Args();
        JsonCommandLineParser.newBuilder().addObject(args).build()
            .parse(new String[]{"---INLINE-JSON", "{\"failedDocumentStreamS3Region\": \"us-east-1\"}"});

        assertThat(args.failedDocumentStreamArgs.failedDocumentStreamS3Bucket, nullValue());
        assertThat(RfsMigrateDocuments.buildFailedDocumentStreamSink(args, "w", "s"), nullValue());
    }

    @Test
    void buildFailedDocumentStreamSink_returnsNullWhenBucketIsBlank() {
        // A blank bucket reads as "not configured".
        var args = new RfsMigrateDocuments.Args();
        args.failedDocumentStreamArgs.failedDocumentStreamS3Bucket = "   ";
        args.failedDocumentStreamArgs.failedDocumentStreamS3Region = "us-east-1";

        assertThat(RfsMigrateDocuments.buildFailedDocumentStreamSink(args, "w", "s"), nullValue());
    }

    @Test
    void failedDocumentStreamDisabledReason_namesTheMissingBucket() {
        assertThat(RfsMigrateDocuments.FAILED_DOCUMENT_STREAM_DISABLED_REASON,
            equalTo("failed document stream disabled: no --failed-document-stream-s3-bucket configured"));
    }

    @Test
    void logFailedDocumentStreamStatus_reportsDisabledWhenSinkAbsent() {
        assertDoesNotThrow(() -> RfsMigrateDocuments.logFailedDocumentStreamStatus(null, "sess-A"));
    }

    @Test
    void logFailedDocumentStreamStatus_emitsLocationWhenSinkPresent() {
        var sink = mock(FailedDocumentStreamSink.class);
        when(sink.getLocation()).thenReturn("s3://bucket/prefix");

        assertDoesNotThrow(() -> RfsMigrateDocuments.logFailedDocumentStreamStatus(sink, "sess-A"));
        verify(sink, atLeastOnce()).getLocation();
    }

    @Test
    void buildFailedDocumentStreamSink_fallsBackToS3RegionWhenFailedDocumentStreamRegionUnset() {
        var args = new RfsMigrateDocuments.Args();
        args.failedDocumentStreamArgs.failedDocumentStreamS3Bucket = "b";
        args.s3Region = "eu-west-2";   // failedDocumentStreamS3Region intentionally left null
        args.failedDocumentStreamArgs.failedDocumentStreamS3Prefix = "p/";

        var sink = RfsMigrateDocuments.buildFailedDocumentStreamSink(args, "w", "s");
        // Just confirming it builds without throwing — region resolution
        // happens internally and a missing region would throw.
        assertThat(sink, notNullValue());
        assertThat(sink.getLocation(), startsWith("s3://b/p/session=s/"));
    }

    @Test
    void buildFailedDocumentStreamSink_throwsWhenBucketSetButNoRegionAnywhere() {
        var args = new RfsMigrateDocuments.Args();
        args.failedDocumentStreamArgs.failedDocumentStreamS3Bucket = "b";
        // Neither failedDocumentStreamS3Region nor s3Region is set.
        assertThrows(ParameterException.class,
            () -> RfsMigrateDocuments.buildFailedDocumentStreamSink(args, "w", "s"));
    }

    // ---- flushFailedDocumentStreamBeforeComplete ------------------------------------------

    @Test
    void flushFailedDocumentStreamBeforeComplete_nullSinkAllowsMarkComplete() {
        assertThat(RfsMigrateDocuments.flushFailedDocumentStreamBeforeComplete(null, "item-1"), is(true));
    }

    @Test
    void flushFailedDocumentStreamBeforeComplete_successfulFlushAllowsMarkComplete() {
        var sink = mock(FailedDocumentStreamSink.class);
        when(sink.flush()).thenReturn(Mono.empty());
        assertThat(RfsMigrateDocuments.flushFailedDocumentStreamBeforeComplete(sink, "item-1"), is(true));
    }

    @Test
    void flushFailedDocumentStreamBeforeComplete_failedFlushBlocksMarkComplete() {
        var sink = mock(FailedDocumentStreamSink.class);
        when(sink.flush()).thenReturn(Mono.error(new RuntimeException("S3 PutObject failed")));
        // We do NOT mark the work item complete; the lease should expire so a
        // successor can re-emit any unflushed failed document stream records.
        assertThat(RfsMigrateDocuments.flushFailedDocumentStreamBeforeComplete(sink, "item-1"), is(false));
    }

    @Test
    void flushFailedDocumentStreamBeforeComplete_timeoutBlocksMarkComplete() {
        var sink = mock(FailedDocumentStreamSink.class);
        // A Mono that never completes simulates an S3 stall — should be timed
        // out by the helper's 5-minute block().  Use a small reactor delay so
        // we don't actually wait minutes in the test: the contract still holds
        // since the helper catches Exception.
        Mono<Void> stalled = Mono.<Void>never().timeout(Duration.ofMillis(50));
        when(sink.flush()).thenReturn(stalled);
        assertThat(RfsMigrateDocuments.flushFailedDocumentStreamBeforeComplete(sink, "item-1"), is(false));
    }

    // ---- isCoordinatorWorkAlreadyDone ------------------------------------

    @Test
    void isCoordinatorWorkAlreadyDone_trueWhenCoordinatorReportsNoPending() throws Exception {
        var coordinator = mock(IWorkCoordinator.class);
        // workItemsNotYetComplete returns false => nothing pending => we can short-circuit.
        when(coordinator.workItemsNotYetComplete(any())).thenReturn(false);

        var context = mock(RootDocumentMigrationContext.class, RETURNS_DEEP_STUBS);
        assertThat(RfsMigrateDocuments.isCoordinatorWorkAlreadyDone(coordinator, context), is(true));
    }

    @Test
    void isCoordinatorWorkAlreadyDone_falseWhenCoordinatorReportsPending() throws Exception {
        var coordinator = mock(IWorkCoordinator.class);
        when(coordinator.workItemsNotYetComplete(any())).thenReturn(true);

        var context = mock(RootDocumentMigrationContext.class, RETURNS_DEEP_STUBS);
        assertThat(RfsMigrateDocuments.isCoordinatorWorkAlreadyDone(coordinator, context), is(false));
    }

    @Test
    void isCoordinatorWorkAlreadyDone_falseWhenCoordinatorThrows() throws Exception {
        // Typical case: first run, work-coordination index doesn't exist yet.
        // The helper must swallow the exception so the caller falls through
        // to the normal flow (which creates the index).
        var coordinator = mock(IWorkCoordinator.class);
        when(coordinator.workItemsNotYetComplete(any()))
            .thenThrow(new IOException("index_not_found_exception"));

        var context = mock(RootDocumentMigrationContext.class, RETURNS_DEEP_STUBS);
        assertThat(RfsMigrateDocuments.isCoordinatorWorkAlreadyDone(coordinator, context), is(false));
    }

    // ---- DurationConverter / DeltaModeConverter / IndexNameValidator ----

    @Test
    void durationConverter_parsesIso8601Strings() {
        var converter = new RfsMigrateDocuments.DurationConverter();
        assertThat(converter.convert("PT10M"), equalTo(Duration.ofMinutes(10)));
        assertThat(converter.convert("PT2H30M"), equalTo(Duration.ofHours(2).plusMinutes(30)));
    }

    @Test
    void deltaModeConverter_acceptsCaseInsensitiveEnumNames() {
        var converter = new RfsMigrateDocuments.DeltaModeConverter();
        // The user-facing flag is case-insensitive: "diff", "DIFF", and "Diff"
        // must all resolve to the same enum value to avoid a frustrating UX.
        DeltaMode anyValid = DeltaMode.values()[0];
        assertThat(converter.convert(anyValid.name().toLowerCase()), is(anyValid));
        assertThat(converter.convert(anyValid.name()), is(anyValid));
    }

    @Test
    void deltaModeConverter_rejectsUnknownModeWithListOfValidValues() {
        var converter = new RfsMigrateDocuments.DeltaModeConverter();
        var thrown = assertThrows(ParameterException.class, () -> converter.convert("nope"));
        // The error message lists every legal value so the user can pick one
        // without grepping the source code.
        assertThat(thrown.getMessage(), startsWith("Invalid delta mode: nope"));
        for (DeltaMode mode : DeltaMode.values()) {
            assertThat(thrown.getMessage(), org.hamcrest.Matchers.containsString(mode.name()));
        }
    }

    @Test
    void indexNameValidator_acceptsAlphanumericAndDashes() {
        var validator = new RfsMigrateDocuments.IndexNameValidator();
        // None of these should throw — they're all in the allowed alphabet.
        assertDoesNotThrow(() -> validator.validate("--session-name", "logs-2024-01"));
        assertDoesNotThrow(() -> validator.validate("--session-name", ""));     // empty is OK
        assertDoesNotThrow(() -> validator.validate("--session-name", "ABC123"));
    }

    @Test
    void indexNameValidator_rejectsDisallowedCharacters() {
        var validator = new RfsMigrateDocuments.IndexNameValidator();
        assertThrows(ParameterException.class,
            () -> validator.validate("--session-name", "has spaces"));
        assertThrows(ParameterException.class,
            () -> validator.validate("--session-name", "has/slash"));
        assertThrows(ParameterException.class,
            () -> validator.validate("--session-name", "has_underscore"));
    }

    // ---- validateArgs ---------------------------------------------------

    private static RfsMigrateDocuments.Args validEsArgs() {
        var args = new RfsMigrateDocuments.Args();
        args.snapshotName = "my-snap";
        args.luceneDir = "/tmp/lucene";
        args.sourceVersion = Version.fromString("ES_7.10");
        args.repoUri = "/tmp/snapshot";  // bare absolute path -> file:// repo
        return args;
    }

    @Test
    void validateArgs_acceptsFileRepoUriOnly() {
        assertDoesNotThrow(() -> RfsMigrateDocuments.validateArgs(validEsArgs()));
    }

    @Test
    void validateArgs_collectionRoutingRequiresRoutedTargetAndViceVersa() {
        var routedWithoutTable = validEsArgs();
        routedWithoutTable.targetArgs.collectionRouted = true;
        assertThrows(ParameterException.class, () -> RfsMigrateDocuments.validateArgs(routedWithoutTable));

        var tableWithoutRoutedTarget = validEsArgs();
        tableWithoutRoutedTarget.collectionRouting = "{\"staticCollectionRouting\": [{\"sourceIndex\": \"a\", \"collection\": \"c\"}]}";
        assertThrows(ParameterException.class, () -> RfsMigrateDocuments.validateArgs(tableWithoutRoutedTarget));

        var invalidTable = validEsArgs();
        invalidTable.targetArgs.collectionRouted = true;
        invalidTable.collectionRouting = "[]";
        assertThrows(ParameterException.class, () -> RfsMigrateDocuments.validateArgs(invalidTable));

        var routed = validEsArgs();
        routed.targetArgs.collectionRouted = true;
        routed.collectionRouting = "{\"staticCollectionRouting\": [{\"sourceIndex\": \"a\", \"collection\": \"c\"}]}";
        assertDoesNotThrow(() -> RfsMigrateDocuments.validateArgs(routed));
    }

    @Test
    void inlineJson_collectionRoutingArgsParseAsTheWorkflowSendsThem() throws Exception {
        // The workflow sends the routing table as a JSON string and the target flag as a boolean
        var routing = "{\"regexCollectionRouting\": [{\"sourceIndex\": \"(.+)-\\\\d{4}\", \"collection\": \"$1\"}]}";
        var json = new ObjectMapper().writeValueAsString(Map.of(
            "targetHost", "https://123456789012.aoss.us-east-1.on.aws",
            "targetAwsRegion", "us-east-1",
            "targetAwsServiceSigningName", "aoss",
            "targetCollectionRouted", true,
            "collectionRouting", routing));
        var args = new RfsMigrateDocuments.Args();
        JsonCommandLineParser.newBuilder().addObject(args).build()
            .parse(new String[]{"---INLINE-JSON", json});

        assertThat(args.collectionRouting, equalTo(routing));
        assertTrue(args.targetArgs.toConnectionContext().isCollectionRouted());
        var parsed = ServerlessCollectionRouting.forTarget(args.collectionRouting, args.targetArgs.collectionRouted);
        assertThat(parsed.orElseThrow().resolve("tenant-a-2024"), equalTo(Optional.of("tenant-a")));
    }

    @Test
    void buildCollectionRoutedTarget_onlyForRoutedTargets() {
        var factory = mock(OpenSearchClientFactory.class);
        when(factory.detectServerlessCollectionType("tenant-a")).thenReturn(ServerlessCollectionType.VECTOR);

        assertThat(RfsMigrateDocuments.buildCollectionRoutedTarget(validEsArgs(), factory), nullValue());

        var args = validEsArgs();
        args.targetArgs.collectionRouted = true;
        args.collectionRouting = "{\"regexCollectionRouting\": [{\"sourceIndex\": \"(.+)-\\\\d{4}\", \"collection\": \"$1\"}]}";
        var target = RfsMigrateDocuments.buildCollectionRoutedTarget(args, factory);

        assertThat(target.collectionFor("tenant-a-2024"), equalTo("tenant-a"));
        assertTrue(target.allowServerGeneratedIds("tenant-a"));
    }

    @Test
    void resolveUseServerGeneratedIds_skipsGlobalProbeForRoutedTargets() {
        var factory = mock(OpenSearchClientFactory.class);
        when(factory.detectServerlessCollectionType()).thenReturn(ServerlessCollectionType.TIMESERIES);
        var routedTarget = mock(CollectionRoutedTarget.class);

        assertFalse(RfsMigrateDocuments.resolveUseServerGeneratedIds(RfsMigrateDocuments.ServerGeneratedIdMode.AUTO, factory, routedTarget));
        verify(factory, times(0)).detectServerlessCollectionType();
        assertTrue(RfsMigrateDocuments.resolveUseServerGeneratedIds(RfsMigrateDocuments.ServerGeneratedIdMode.AUTO, factory, null));
        assertTrue(RfsMigrateDocuments.resolveUseServerGeneratedIds(RfsMigrateDocuments.ServerGeneratedIdMode.ALWAYS, factory, routedTarget));
        assertFalse(RfsMigrateDocuments.resolveUseServerGeneratedIds(RfsMigrateDocuments.ServerGeneratedIdMode.NEVER, factory, null));
    }

    @Test
    void validateCollectionRouting_checksOnlyAllowlistedIndices() {
        var source = mock(DocumentSource.class);
        when(source.listCollections()).thenReturn(List.of("tenant-a-2024", "unrouted", ".system"));
        var routing = ServerlessCollectionRouting.fromJson(
            "{\"regexCollectionRouting\": [{\"sourceIndex\": \"(.+)-\\\\d{4}\", \"collection\": \"$1\"}]}").orElseThrow();
        var target = new CollectionRoutedTarget(routing, collection -> false);

        assertDoesNotThrow(() -> RfsMigrateDocuments.validateCollectionRouting(null, source, List.of()));
        assertDoesNotThrow(() -> RfsMigrateDocuments.validateCollectionRouting(target, source, List.of("tenant-a-2024")));
        var e = assertThrows(IllegalArgumentException.class,
            () -> RfsMigrateDocuments.validateCollectionRouting(target, source, List.of()));
        assertThat(e.getMessage(), equalTo("No collection routing entry matches source indices [unrouted]"));
    }

    @Test
    void requiresServerGeneratedIds_probesOnlyInAutoMode() {
        var factory = mock(OpenSearchClientFactory.class);
        when(factory.detectServerlessCollectionType("ts"))
            .thenReturn(ServerlessCollectionType.TIMESERIES);
        when(factory.detectServerlessCollectionType("search"))
            .thenReturn(ServerlessCollectionType.SEARCH);

        assertTrue(RfsMigrateDocuments.requiresServerGeneratedIds(RfsMigrateDocuments.ServerGeneratedIdMode.AUTO, factory, "ts"));
        assertFalse(RfsMigrateDocuments.requiresServerGeneratedIds(RfsMigrateDocuments.ServerGeneratedIdMode.AUTO, factory, "search"));
        assertTrue(RfsMigrateDocuments.requiresServerGeneratedIds(RfsMigrateDocuments.ServerGeneratedIdMode.ALWAYS, factory, "search"));
        assertFalse(RfsMigrateDocuments.requiresServerGeneratedIds(RfsMigrateDocuments.ServerGeneratedIdMode.NEVER, factory, "ts"));
        verify(factory, times(2)).detectServerlessCollectionType(anyString());
    }

    @Test
    void validateArgs_acceptsS3RepoWithLocalDirAndRegion() {
        var args = validEsArgs();
        args.repoUri = "s3://bucket/key";
        args.localDir = "/tmp/s3";
        args.s3Region = "us-east-1";
        assertDoesNotThrow(() -> RfsMigrateDocuments.validateArgs(args));
    }

    @Test
    void validateArgs_acceptsGcsRepoWithLocalDir() {
        var args = validEsArgs();
        args.repoUri = "gs://bucket/key";
        args.localDir = "/tmp/gcs";
        assertDoesNotThrow(() -> RfsMigrateDocuments.validateArgs(args));
    }

    @Test
    void validateArgs_rejectsGcsRepoWithoutLocalDir() {
        var args = validEsArgs();
        args.repoUri = "gs://bucket/key";
        var thrown = assertThrows(ParameterException.class,
            () -> RfsMigrateDocuments.validateArgs(args));
        assertThat(thrown.getMessage(), equalTo("If a GCS repo is being used, --local-dir must be set."));
    }

    @Test
    void validateArgs_rejectsMissingSnapshotName() {
        var args = validEsArgs();
        args.snapshotName = null;
        var thrown = assertThrows(ParameterException.class,
            () -> RfsMigrateDocuments.validateArgs(args));
        assertThat(thrown.getMessage(), org.hamcrest.Matchers.containsString("--snapshot-name"));
    }

    @Test
    void validateArgs_rejectsMissingLuceneDir() {
        var args = validEsArgs();
        args.luceneDir = null;
        var thrown = assertThrows(ParameterException.class,
            () -> RfsMigrateDocuments.validateArgs(args));
        assertThat(thrown.getMessage(), org.hamcrest.Matchers.containsString("--lucene-dir"));
    }

    @Test
    void validateArgs_rejectsMissingSourceVersion() {
        var args = validEsArgs();
        args.sourceVersion = null;
        var thrown = assertThrows(ParameterException.class,
            () -> RfsMigrateDocuments.validateArgs(args));
        assertThat(thrown.getMessage(), org.hamcrest.Matchers.containsString("--source-version"));
    }

    @Test
    void validateArgs_rejectsS3RepoWithoutLocalDirOrRegion() {
        // An s3:// repo requires both --local-dir and --s3-region.
        var args = validEsArgs();
        args.repoUri = "s3://bucket/key";  // neither --local-dir nor --s3-region set
        var thrown = assertThrows(ParameterException.class,
            () -> RfsMigrateDocuments.validateArgs(args));
        assertThat(thrown.getMessage(), org.hamcrest.Matchers.containsString("--local-dir"));
    }

    @Test
    void validateArgs_rejectsMissingRepoUri() {
        var args = validEsArgs();
        args.repoUri = null;
        var thrown = assertThrows(ParameterException.class,
            () -> RfsMigrateDocuments.validateArgs(args));
        assertThat(thrown.getMessage(), org.hamcrest.Matchers.containsString("--repo-uri"));
    }

    @Test
    void validateArgs_rejectsPreviousSnapshotWithoutDeltaMode() {
        var args = validEsArgs();
        args.experimental.previousSnapshotName = "prev";
        // delta mode intentionally left null
        var thrown = assertThrows(ParameterException.class,
            () -> RfsMigrateDocuments.validateArgs(args));
        assertThat(thrown.getMessage(), org.hamcrest.Matchers.containsString("--experimental-delta-mode"));
    }

    @Test
    void validateArgs_rejectsDeltaModeWithoutPreviousSnapshot() {
        var args = validEsArgs();
        args.experimental.experimentalDeltaMode = DeltaMode.values()[0];
        // previousSnapshotName intentionally left null
        var thrown = assertThrows(ParameterException.class,
            () -> RfsMigrateDocuments.validateArgs(args));
        assertThat(thrown.getMessage(),
            org.hamcrest.Matchers.containsString("--experimental-previous-snapshot-name"));
    }

    @Test
    void validateArgs_solr_rejectsMissingCoordinatorHost() {
        // Solr-flavored runs always need a separate coordinator cluster because
        // the source itself isn't OpenSearch.
        var args = new RfsMigrateDocuments.Args();
        args.sourceVersion = Version.fromString("SOLR_8.11");
        args.repoUri = "/tmp/solr";
        // coordinatorArgs.host left null
        var thrown = assertThrows(ParameterException.class,
            () -> RfsMigrateDocuments.validateArgs(args));
        assertThat(thrown.getMessage(), org.hamcrest.Matchers.containsString("--coordinator-host"));
    }

    @Test
    void validateArgs_solr_rejectsGcsRepoUpFront() {
        // Solr supports only file:// and s3:// for its backup location. Without an
        // exhaustive switch, gs:// passed validation here and failed much later in
        // buildSolrSourceFactory, after the migration had already started.
        var args = new RfsMigrateDocuments.Args();
        args.sourceVersion = Version.fromString("SOLR_8.11");
        args.repoUri = "gs://bucket/key";
        args.localDir = "/tmp/gcs";
        args.coordinatorArgs.host = "http://localhost:9200";
        var thrown = assertThrows(ParameterException.class,
            () -> RfsMigrateDocuments.validateArgs(args));
        assertThat(thrown.getMessage(), org.hamcrest.Matchers.containsString("file:// or s3://"));
    }

    @Test
    void validateArgs_solr_rejectsMissingBackupSource() {
        var args = new RfsMigrateDocuments.Args();
        args.sourceVersion = Version.fromString("SOLR_8.11");
        // No --repo-uri set at all.
        var thrown = assertThrows(ParameterException.class,
            () -> RfsMigrateDocuments.validateArgs(args));
        assertThat(thrown.getMessage(), org.hamcrest.Matchers.containsString("Solr"));
    }

    // ---- buildCompletionRetryConfig & calculateTotalRetryWindowSeconds --

    @Test
    void buildCompletionRetryConfig_propagatesCliValues() {
        var args = new RfsMigrateDocuments.Args();
        args.coordinatorRetryMaxRetries = 4;
        args.coordinatorRetryInitialDelayMs = 250;
        args.coordinatorRetryMaxDelayMs = 16_000;

        var cfg = RfsMigrateDocuments.buildCompletionRetryConfig(args);
        assertThat(cfg.maxRetries(), is(4));
        assertThat(cfg.initialDelayMs(), is(250L));
        assertThat(cfg.maxDelayMs(), is(16_000L));
    }

    @Test
    void calculateTotalRetryWindowSeconds_zeroRetriesYieldsZeroWindow() {
        // With maxRetries=0 there's no sleep at all between attempts.
        var cfg = new org.opensearch.migrations.bulkload.workcoordination.OpenSearchWorkCoordinator
            .CompletionRetryConfig(0, 1000, 64_000);
        assertThat(RfsMigrateDocuments.calculateTotalRetryWindowSeconds(cfg), is(0L));
    }

    @Test
    void calculateTotalRetryWindowSeconds_matchesManualSumForKnownDelays() {
        // 3 retries with 1s initial, doubling each time, capped at 4s:
        // delays = [1000, 2000, 4000] ms  -> total = 7000 ms -> 7 seconds.
        var cfg = new org.opensearch.migrations.bulkload.workcoordination.OpenSearchWorkCoordinator
            .CompletionRetryConfig(3, 1000, 4000);
        assertThat(RfsMigrateDocuments.calculateTotalRetryWindowSeconds(cfg), is(7L));
    }

    @Test
    void calculateTotalRetryWindowSeconds_capsAtMaxDelay() {
        // 5 retries, initial 1000ms, maxDelay 2000ms: delays double until cap,
        // then stay capped. [1000, 2000, 2000, 2000, 2000] = 9000 ms = 9 s.
        var cfg = new org.opensearch.migrations.bulkload.workcoordination.OpenSearchWorkCoordinator
            .CompletionRetryConfig(5, 1000, 2000);
        assertThat(RfsMigrateDocuments.calculateTotalRetryWindowSeconds(cfg), is(9L));
        // Sanity: longer windows produce strictly larger totals.
        var bigger = new org.opensearch.migrations.bulkload.workcoordination.OpenSearchWorkCoordinator
            .CompletionRetryConfig(5, 1000, 32_000);
        assertThat(RfsMigrateDocuments.calculateTotalRetryWindowSeconds(bigger),
            greaterThan(RfsMigrateDocuments.calculateTotalRetryWindowSeconds(cfg)));
    }

    // ---- buildDocumentExceptionAllowlist --------------------------------

    @Test
    void buildDocumentExceptionAllowlist_emptyByDefault() {
        var args = new RfsMigrateDocuments.Args();
        var allowlist = RfsMigrateDocuments.buildDocumentExceptionAllowlist(args);
        assertThat(allowlist.isAllowed("version_conflict_engine_exception"), is(false));
    }

    @Test
    void buildDocumentExceptionAllowlist_includesCliEntries() {
        var args = new RfsMigrateDocuments.Args();
        args.allowedDocExceptionTypes = java.util.List.of(
            "version_conflict_engine_exception", "mapper_parsing_exception");
        var allowlist = RfsMigrateDocuments.buildDocumentExceptionAllowlist(args);
        assertThat(allowlist.isAllowed("version_conflict_engine_exception"), is(true));
        assertThat(allowlist.isAllowed("mapper_parsing_exception"), is(true));
        assertThat(allowlist.isAllowed("strict_dynamic_mapping_exception"), is(false));
    }

    // ---- getSuccessorWorkItemIds ----------------------------------------

    @Test
    void getSuccessorWorkItemIds_buildsSuccessorAtCurrentCheckpoint() {
        // The successor work item keeps the same checkpoint number so the next
        // worker resumes from where this one stopped — this also handles
        // 1:many doc splits (re-processing the last cursor is intentional).
        var workItem = new IWorkCoordinator.WorkItemAndDuration.WorkItem("movies", 2, 0L);
        var workItemAndDuration = new IWorkCoordinator.WorkItemAndDuration(
            Instant.now().plusSeconds(60), workItem);
        var cursor = new WorkItemCursor(42L);

        var successors = RfsMigrateDocuments.getSuccessorWorkItemIds(workItemAndDuration, cursor);
        // Exactly one successor: same index/shard, restart from the cursor.
        assertThat(successors, contains(
            new IWorkCoordinator.WorkItemAndDuration.WorkItem("movies", 2, 42L).toString()));
    }

    @Test
    void getSuccessorWorkItemIds_throwsWhenWorkItemNull() {
        assertThrows(IllegalStateException.class,
            () -> RfsMigrateDocuments.getSuccessorWorkItemIds(null, new WorkItemCursor(0L)));
    }

    // ---- NoWorkLeftException ---------------------------------------------

    @Test
    void noWorkLeftException_carriesMessage() {
        var ex = new RfsMigrateDocuments.NoWorkLeftException("done");
        assertThat(ex.getMessage(), equalTo("done"));
    }
}
