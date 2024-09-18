package org.opensearch.migrations.commands;

import org.junit.jupiter.api.Test;

import org.opensearch.migrations.MetadataMigration;
import org.opensearch.migrations.Version;
import org.opensearch.migrations.metadata.tracing.RootMetadataMigrationContext;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mock;

class MigrateTest {

    @Test
    void migrate_failsInvalidParameters() {
        var args = new MigrateArgs();
        var context = mock(RootMetadataMigrationContext.class);
        var meta = new MetadataMigration();

        var configureSource = meta.migrate(args).execute(context);

        assertThat(configureSource.getExitCode(), equalTo(Migrate.INVALID_PARAMETER_CODE));
    }

    @Test
    void migrate_failsUnexpectedException() {
        var args = new MigrateArgs();
        args.sourceVersion = Version.fromString("ES 7.10");
        args.fileSystemRepoPath = "";
        var context = mock(RootMetadataMigrationContext.class);
        var meta = new MetadataMigration();

        var configureSource = meta.migrate(args).execute(context);

        assertThat(configureSource.getExitCode(), equalTo(Migrate.UNEXPECTED_FAILURE_CODE));
    }
}
