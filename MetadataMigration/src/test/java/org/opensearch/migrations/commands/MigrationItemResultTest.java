package org.opensearch.migrations.commands;

import org.opensearch.migrations.cli.Clusters;
import org.opensearch.migrations.cli.Items;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.withSettings;

public class MigrationItemResultTest {
    @Test
    void testAsString_fullResults_withMessage() {
        var clusters = mock(Clusters.class, withSettings().defaultAnswer(RETURNS_DEEP_STUBS));
        var items = mock(Items.class);
        var testObject = EvaluateResult.builder()
            .clusters(clusters)
            .items(items)
            .exitCode(10)
            .errorMessage("Full results")
            .build();

        var result = testObject.asCliOutput();
        assertThat(result, containsString("Issue(s) detected"));
        assertThat(result, containsString("Issues:"));

        verify(clusters).asCliOutput();
        verify(clusters).getSource();
        verify(clusters).getTarget();
        verify(items).asCliOutput();
        verify(items, times(1)).getAllErrors();
        verifyNoMoreInteractions(items, clusters);
    }

    @Test
    void testAsString_fullResults_withNoMessage() {
        var clusters = mock(Clusters.class, withSettings().defaultAnswer(RETURNS_DEEP_STUBS));
        var items = mock(Items.class);
        var testObject = EvaluateResult.builder()
            .clusters(clusters)
            .items(items)
            .exitCode(10)
            .build();

        var result = testObject.asCliOutput();
        assertThat(result, containsString("10 issue(s) detected"));
        verify(items).asCliOutput();
        verify(items, times(2)).getAllErrors();
        verifyNoMoreInteractions(items);
    }

    @Test
    void testAsString_noItems() {
        var clusters = mock(Clusters.class, withSettings().defaultAnswer(RETURNS_DEEP_STUBS));
        var testObject = EvaluateResult.builder()
            .clusters(clusters)
            .exitCode(0)
            .build();

        var result = testObject.asCliOutput();
        assertThat(result, containsString("0 issue(s) detected"));
    }

    @Test
    void testAsString_nothing() {
        var testObject = EvaluateResult.builder()
            .exitCode(0)
            .build();

        var result = testObject.asCliOutput();
        assertThat(result, containsString(  "No source was defined"));
        assertThat(result, containsString(  "No target was defined"));
    }
}
