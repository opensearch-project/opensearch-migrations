package org.opensearch.migrations.commands;

import org.opensearch.migrations.MetadataMigration;
import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.SnapshotReadFailures;
import org.opensearch.migrations.bulkload.common.SnapshotRepo;
import org.opensearch.migrations.metadata.tracing.RootMetadataMigrationContext;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

class MigrateTest {

    @Test
    void migrate_failsInvalidParameters() {
        var args = new MigrateArgs();
        var context = mock(RootMetadataMigrationContext.class);
        var meta = new MetadataMigration();

        var result = meta.migrate(args).execute(context);

        assertThat(result.getExitCode(), equalTo(Migrate.INVALID_PARAMETER_CODE));
        assertThat(result.getErrorMessage(), equalTo("Invalid parameter: No details on the source cluster found, please supply a connection details or a snapshot"));
    }

    @Test
    void migrate_failsUnexpectedException() {
        var args = new MigrateArgs();
        args.sourceVersion = Version.fromString("ES 7.10");
        args.fileSystemRepoPath = "";
        var context = mock(RootMetadataMigrationContext.class);
        var meta = new MetadataMigration();

        var result = meta.migrate(args).execute(context);

        assertThat(result.getExitCode(), equalTo(Migrate.UNEXPECTED_FAILURE_CODE));
        assertThat(result.getErrorMessage(), containsString("Unexpected failure: No host was found"));
    }

    @Test
    void migrate_classifiesSnapshotReadFailureWithDedicatedExitCode() {
        var args = new MigrateArgs();
        args.snapshotName = "snap1";
        var context = mock(RootMetadataMigrationContext.class);
        var meta = new MetadataMigration();

        var migrate = spy(meta.migrate(args));
        // A snapshot read failure surfacing during source/snapshot setup, wrapped the way real
        // callers wrap it (e.g. inside a metadata-read wrapper).
        var readFailure = new SnapshotRepo.CannotParseRepoFile("corrupt repo metadata: index-0");
        doThrow(new RuntimeException("reading snapshot failed", readFailure))
            .when(migrate).createClusters();

        var result = migrate.execute(context);

        assertThat(result.getExitCode(), equalTo(SnapshotReadFailures.EXIT_CODE));
        assertThat(result.getErrorMessage(), containsString("Non-retriable snapshot read failure"));
        assertThat(result.getErrorMessage(), containsString("corrupt repo metadata: index-0"));
        assertThat(result.getErrorMessage(), containsString("snap1"));
    }

    @Test
    void migrate_snapshotReadFailureNamesFilesystemRepo() {
        var args = new MigrateArgs();
        args.snapshotName = "snap2";
        args.fileSystemRepoPath = "/backups/repo";
        var context = mock(RootMetadataMigrationContext.class);
        var meta = new MetadataMigration();

        var migrate = spy(meta.migrate(args));
        doThrow(new RuntimeException("read failed",
            new SnapshotRepo.CannotParseRepoFile("bad index-0"))).when(migrate).createClusters();

        var result = migrate.execute(context);

        assertThat(result.getExitCode(), equalTo(SnapshotReadFailures.EXIT_CODE));
        assertThat(result.getErrorMessage(), containsString("repo=/backups/repo"));
    }

    @Test
    void migrate_failsUnexpectedExceptionInnerMessage() {
        var args = new EvaluateArgs();
        var meta = new MetadataMigration();
        var context = mock(RootMetadataMigrationContext.class);
 
        var evaluate = spy(meta.migrate(args));
        doThrow(new RuntimeException("Outer", new RuntimeException("Inner"))).when(evaluate).createClusters();

        var results = evaluate.execute(context);

        assertThat(results.getExitCode(), equalTo(Evaluate.UNEXPECTED_FAILURE_CODE));
        assertThat(results.getErrorMessage(), equalTo("Unexpected failure: Outer, inner cause: Inner"));
    }
}
