package org.opensearch.migrations.commands;

import org.junit.jupiter.api.Test;

import org.opensearch.migrations.MetadataArgs;
import org.opensearch.migrations.MetadataMigration;
import org.opensearch.migrations.metadata.tracing.RootMetadataMigrationContext;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mock;

class MigrateTest {

    @Test
    void migrate_failsInvalidInvalidParameters() {
        var args = new MetadataArgs();
        var context = mock(RootMetadataMigrationContext.class);
        var meta = new MetadataMigration(args);

        var configureSource = meta.migrate().execute(context);

        assertThat(configureSource.getExitCode(), equalTo(999));
    }

    @Test
    void migrate_failsUnexpectedException() {
        var args = new MetadataArgs();
        args.fileSystemRepoPath = "";
        var context = mock(RootMetadataMigrationContext.class);
        var meta = new MetadataMigration(args);

        var configureSource = meta.migrate().execute(context);

        assertThat(configureSource.getExitCode(), equalTo(888));
    }
}
