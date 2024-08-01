package org.opensearch.migrations.commands;

import org.junit.jupiter.api.Test;

import org.opensearch.migrations.MetadataMigration;
import org.opensearch.migrations.clusters.Sources;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mock;

class ConfigureTest {

    @Test
    void configureSource_notImplemented() {
        var meta = new MetadataMigration(mock(MetadataMigration.Args.class));

        var configureSource = meta.configure()
            .source(Sources.withHost("https://localhost:9200"))
            .execute();

        assertThat(configureSource.getExitCode(), equalTo(9999));
    }
}
