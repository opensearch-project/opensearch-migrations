package org.opensearch.migrations.commands;

import org.opensearch.migrations.MetadataMigration;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

class ConfigureTest {

    @Test
    void configureSource_notImplemented() {
        var meta = new MetadataMigration();

        var configureSource = meta.configure()
            .execute();

        assertThat(configureSource.getExitCode(), equalTo(9999));
    }
}
