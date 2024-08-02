package org.opensearch.migrations.commands;

import org.junit.jupiter.api.Test;

import org.opensearch.migrations.MetadataArgs;
import org.opensearch.migrations.MetadataMigration;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mock;

class ConfigureTest {

    @Test
    void configureSource_notImplemented() {
        var meta = new MetadataMigration(mock(MetadataArgs.class));

        var configureSource = meta.configure()
            .execute();

        assertThat(configureSource.getExitCode(), equalTo(9999));
    }
}
