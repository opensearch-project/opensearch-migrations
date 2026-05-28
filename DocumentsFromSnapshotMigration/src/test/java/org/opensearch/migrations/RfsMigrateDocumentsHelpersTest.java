package org.opensearch.migrations;

import java.io.IOException;
import java.time.Duration;

import org.opensearch.migrations.bulkload.workcoordination.IWorkCoordinator;
import org.opensearch.migrations.reindexer.dlq.DlqSink;
import org.opensearch.migrations.reindexer.tracing.RootDocumentMigrationContext;

import com.beust.jcommander.ParameterException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the DLQ / coordinator helper methods on {@link RfsMigrateDocuments}.
 * The helpers are package-private specifically so tests in this package can
 * exercise them without spinning up an actual cluster.
 */
class RfsMigrateDocumentsHelpersTest {

    // ---- resolveSessionId -------------------------------------------------

    @Test
    void resolveSessionId_prefersExplicitCliFlag() {
        var args = new RfsMigrateDocuments.Args();
        args.dlqArgs.dlqSessionId = "explicit-session";
        // Even if ARGO_WORKFLOW_UID happens to be set in the env, the explicit
        // CLI flag must win.
        assertThat(RfsMigrateDocuments.resolveSessionId(args, "ignored-worker"),
            equalTo("explicit-session"));
    }

    @Test
    void resolveSessionId_blankCliFlagFallsThrough() {
        var args = new RfsMigrateDocuments.Args();
        args.dlqArgs.dlqSessionId = "   ";  // blank — should be skipped
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

    // ---- buildDlqSink -----------------------------------------------------

    @Test
    void buildDlqSink_returnsNullWhenNoBucketConfigured() {
        var args = new RfsMigrateDocuments.Args();
        // Don't set --dlq-s3-bucket. If the env doesn't supply a default bucket
        // either, the sink should be null (DLQ disabled).
        if (System.getenv("MIGRATIONS_DEFAULT_S3_BUCKET") == null) {
            assertThat(RfsMigrateDocuments.buildDlqSink(args, "w", "sess"), nullValue());
        }
    }

    @Test
    void buildDlqSink_buildsSinkWithExplicitBucketAndRegion() {
        var args = new RfsMigrateDocuments.Args();
        args.dlqArgs.dlqS3Bucket = "my-dlq-bucket";
        args.dlqArgs.dlqS3Region = "us-east-1";
        args.dlqArgs.dlqS3Prefix = "rfs-dlq/";

        var sink = RfsMigrateDocuments.buildDlqSink(args, "worker-1", "sess-A");

        assertThat(sink, notNullValue());
        // S3DlqSink#getLocation embeds bucket, prefix, and session id — using it
        // as a single read-back avoids reflection on the sink's private fields.
        assertThat(sink.getLocation(),
            equalTo("s3://my-dlq-bucket/rfs-dlq/session=sess-A/"));
    }

    @Test
    void buildDlqSink_fallsBackToS3RegionWhenDlqRegionUnset() {
        var args = new RfsMigrateDocuments.Args();
        args.dlqArgs.dlqS3Bucket = "b";
        args.s3Region = "eu-west-2";   // dlqS3Region intentionally left null
        args.dlqArgs.dlqS3Prefix = "p/";

        var sink = RfsMigrateDocuments.buildDlqSink(args, "w", "s");
        // Just confirming it builds without throwing — region resolution
        // happens internally and a missing region would throw.
        assertThat(sink, notNullValue());
        assertThat(sink.getLocation(), startsWith("s3://b/p/session=s/"));
    }

    @Test
    void buildDlqSink_throwsWhenBucketSetButNoRegionAnywhere() {
        var args = new RfsMigrateDocuments.Args();
        args.dlqArgs.dlqS3Bucket = "b";
        // Neither dlqS3Region nor s3Region is set.
        assertThrows(ParameterException.class,
            () -> RfsMigrateDocuments.buildDlqSink(args, "w", "s"));
    }

    // ---- flushDlqBeforeComplete ------------------------------------------

    @Test
    void flushDlqBeforeComplete_nullSinkAllowsMarkComplete() {
        assertThat(RfsMigrateDocuments.flushDlqBeforeComplete(null, "item-1"), is(true));
    }

    @Test
    void flushDlqBeforeComplete_successfulFlushAllowsMarkComplete() {
        var sink = mock(DlqSink.class);
        when(sink.flush()).thenReturn(Mono.empty());
        assertThat(RfsMigrateDocuments.flushDlqBeforeComplete(sink, "item-1"), is(true));
    }

    @Test
    void flushDlqBeforeComplete_failedFlushBlocksMarkComplete() {
        var sink = mock(DlqSink.class);
        when(sink.flush()).thenReturn(Mono.error(new RuntimeException("S3 PutObject failed")));
        // We do NOT mark the work item complete; the lease should expire so a
        // successor can re-emit any unflushed DLQ records.
        assertThat(RfsMigrateDocuments.flushDlqBeforeComplete(sink, "item-1"), is(false));
    }

    @Test
    void flushDlqBeforeComplete_timeoutBlocksMarkComplete() {
        var sink = mock(DlqSink.class);
        // A Mono that never completes simulates an S3 stall — should be timed
        // out by the helper's 5-minute block().  Use a small reactor delay so
        // we don't actually wait minutes in the test: the contract still holds
        // since the helper catches Exception.
        Mono<Void> stalled = Mono.<Void>never().timeout(Duration.ofMillis(50));
        when(sink.flush()).thenReturn(stalled);
        assertThat(RfsMigrateDocuments.flushDlqBeforeComplete(sink, "item-1"), is(false));
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
}
