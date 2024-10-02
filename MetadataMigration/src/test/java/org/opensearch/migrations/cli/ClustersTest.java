package org.opensearch.migrations.cli;

import org.opensearch.migrations.cluster.ClusterReader;
import org.opensearch.migrations.cluster.ClusterWriter;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mock;
import static org.opensearch.migrations.matchers.HasLineCount.hasLineCount;

public class ClustersTest {
    @Test
    void testAsString_empty() {
        var clusters = Clusters.builder().build();

        var result = clusters.asCliOutput();

        assertThat(result, containsString("Clusters:"));
        assertThat(result, not(containsString("Source:")));
        assertThat(result, not(containsString("Target:")));
        assertThat(result, hasLineCount(1));
    }

    @Test
    void testAsString_withSource() {
        var clusters = Clusters.builder()
            .source(mock(ClusterReader.class))
            .build();

        var result = clusters.asCliOutput();

        assertThat(result, containsString("Clusters:"));
        assertThat(result, containsString("Source:"));
        assertThat(result, not(containsString("Target:")));
        assertThat(result, hasLineCount(3));
    }

    @Test
    void testAsString_withSourceAndTarget() {
        var clusters = Clusters.builder()
            .source(mock(ClusterReader.class))
            .target(mock(ClusterWriter.class))
            .build();

        var result = clusters.asCliOutput();

        assertThat(result, containsString("Clusters:"));
        assertThat(result, containsString("Source:"));
        assertThat(result, containsString("Target:"));
        assertThat(result, hasLineCount(6));
    }
}
