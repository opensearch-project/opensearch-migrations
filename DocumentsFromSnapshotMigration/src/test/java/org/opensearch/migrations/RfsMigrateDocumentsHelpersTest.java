package org.opensearch.migrations;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

import org.opensearch.migrations.bulkload.common.DeltaMode;
import org.opensearch.migrations.bulkload.workcoordination.IWorkCoordinator;
import org.opensearch.migrations.bulkload.worker.WorkItemCursor;
import org.opensearch.migrations.reindexer.faileddocumentstream.FailedDocumentStreamSink;
import org.opensearch.migrations.reindexer.tracing.RootDocumentMigrationContext;

import com.beust.jcommander.ParameterException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
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
        // Don't set --failed-document-stream-s3-bucket. If the env doesn't supply a default bucket
        // either, the sink should be null (failed document stream disabled).
        if (System.getenv("MIGRATIONS_DEFAULT_S3_BUCKET") == null) {
            assertThat(RfsMigrateDocuments.buildFailedDocumentStreamSink(args, "w", "sess"), nullValue());
        }
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
        args.snapshotLocalDir = "/tmp/snapshot";
        return args;
    }

    @Test
    void validateArgs_acceptsLocalDirOnly() {
        assertDoesNotThrow(() -> RfsMigrateDocuments.validateArgs(validEsArgs()));
    }

    @Test
    void validateArgs_acceptsAllS3Args() {
        var args = validEsArgs();
        args.snapshotLocalDir = null;
        args.s3LocalDir = "/tmp/s3";
        args.s3RepoUri = "s3://bucket/key";
        args.s3Region = "us-east-1";
        assertDoesNotThrow(() -> RfsMigrateDocuments.validateArgs(args));
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
    void validateArgs_rejectsBothSnapshotLocalDirAndS3() {
        var args = validEsArgs();
        // snapshotLocalDir is already set, layering on an S3 arg should be rejected.
        args.s3LocalDir = "/tmp/s3";
        var thrown = assertThrows(ParameterException.class,
            () -> RfsMigrateDocuments.validateArgs(args));
        assertThat(thrown.getMessage(), org.hamcrest.Matchers.containsString("not both"));
    }

    @Test
    void validateArgs_rejectsPartialS3Args() {
        // s3RepoUri without s3Region or s3LocalDir — all-or-nothing.
        var args = validEsArgs();
        args.snapshotLocalDir = null;
        args.s3RepoUri = "s3://bucket/key";
        var thrown = assertThrows(ParameterException.class,
            () -> RfsMigrateDocuments.validateArgs(args));
        assertThat(thrown.getMessage(), org.hamcrest.Matchers.containsString("all of them"));
    }

    @Test
    void validateArgs_rejectsNoSourceAtAll() {
        var args = validEsArgs();
        args.snapshotLocalDir = null;
        var thrown = assertThrows(ParameterException.class,
            () -> RfsMigrateDocuments.validateArgs(args));
        assertThat(thrown.getMessage(), org.hamcrest.Matchers.containsString("--snapshot-local-dir"));
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
        args.snapshotLocalDir = "/tmp/solr";
        // coordinatorArgs.host left null
        var thrown = assertThrows(ParameterException.class,
            () -> RfsMigrateDocuments.validateArgs(args));
        assertThat(thrown.getMessage(), org.hamcrest.Matchers.containsString("--coordinator-host"));
    }

    @Test
    void validateArgs_solr_rejectsMissingBackupSource() {
        var args = new RfsMigrateDocuments.Args();
        args.sourceVersion = Version.fromString("SOLR_8.11");
        // Neither snapshotLocalDir nor S3 args set.
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
