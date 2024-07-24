package org.opensearch.migrations.commands;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

import org.junit.jupiter.api.Test;
import org.opensearch.migrations.MetadataMigration;
import org.opensearch.migrations.clusters.Sources;

public class ConfigureTest {

    private static final int SuccessExitCode = 0;

    @Test
    public void configureSource() {
        var meta = new MetadataMigration();

        var configureSource = meta.configure()
            .source(Sources.withHost("https://localhost:9200"))
            .execute();

        assertThat(configureSource.getExitCode(), equalTo(SuccessExitCode));

    }
}
