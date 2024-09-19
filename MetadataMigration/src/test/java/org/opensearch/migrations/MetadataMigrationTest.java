package org.opensearch.migrations;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.opensearch.migrations.testutils.CloseableLogSetup;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;

public class MetadataMigrationTest {

    @Test
    void testMain_expectTopLevelHelp() throws Exception {
        var testCases = List.of(
            new String[]{},
            new String[]{"-h"},
            new String[]{"--help"}
        );
        for (var testCase : testCases) {
            try (var closeableLogSetup = new CloseableLogSetup(MetadataMigration.class.getName())) {
                MetadataMigration.main(testCase);

                var logEvents = closeableLogSetup.getLogEvents();

                assertThat(logEvents, hasSize(2));
                assertThat(logEvents.get(0), containsString("Command line arguments"));
                assertThat(logEvents.get(1), containsString("Usage: [options] [command] [commandOptions]"));
            }
        }
    }

    @Test
    void testMain_expectCommandHelp() throws Exception {
        var testCases = List.of(
            new String[]{"evaluate", "-h"},
            new String[]{"migrate", "--help"}
        );
        for (var testCase : testCases) {
            try (var closeableLogSetup = new CloseableLogSetup(MetadataMigration.class.getName())) {
                MetadataMigration.main(testCase);

                var logEvents = closeableLogSetup.getLogEvents();

                assertThat(logEvents, hasSize(2));
                assertThat(logEvents.get(0), containsString("Command line arguments"));
                assertThat(logEvents.get(1), containsString("Usage: " + testCase[0] + " [options]"));
            }
        }
    }
}
