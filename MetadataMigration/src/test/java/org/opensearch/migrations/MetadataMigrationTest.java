package org.opensearch.migrations;

import java.util.List;

import org.opensearch.migrations.testutils.CloseableLogSetup;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class MetadataMigrationTest {

    @Test
    void testMain_expectNoPasswordLogged() {
        List<String[]> testCases = List.of(
            new String[]{"--source-password", "mySecretPassword", "--target-host", "http://localhost"},
            new String[]{"--target-password", "mySecretPassword", "--target-host", "http://localhost"}
        );
        for (var testCase : testCases) {
            try (var closeableLogSetup = new CloseableLogSetup(MetadataMigration.class.getName())) {

                assertThrows(com.beust.jcommander.ParameterException.class, () -> MetadataMigration.main(testCase));

                var logEvents = closeableLogSetup.getLogEvents();

                assertFalse(logEvents.stream().anyMatch(s -> s.contains("mySecretPassword")));
            }
        }
    }

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

                assertThat(logEvents, hasSize(1));
                assertThat(logEvents.get(0), containsString("Usage: [options] [command] [commandOptions]"));
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

                assertThat(logEvents, hasSize(1));
                assertThat(logEvents.get(0), containsString("Usage: " + testCase[0] + " [options]"));
            }
        }
    }
}
