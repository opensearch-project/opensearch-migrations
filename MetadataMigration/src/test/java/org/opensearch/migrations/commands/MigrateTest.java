package org.opensearch.migrations.commands;

import org.opensearch.migrations.MetadataMigration;
import org.opensearch.migrations.Version;
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
