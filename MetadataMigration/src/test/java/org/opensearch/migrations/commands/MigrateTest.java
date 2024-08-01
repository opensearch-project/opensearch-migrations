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
    void configureSource_noSourceSet() {
        var meta = new MetadataMigration(mock(MetadataArgs.class));

        var configureSource = meta.migrate()
            .execute(mock(RootMetadataMigrationContext.class));

        assertThat(configureSource.getExitCode(), equalTo(1));
    }
}
