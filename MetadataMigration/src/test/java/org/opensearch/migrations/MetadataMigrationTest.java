package org.opensearch.migrations;

import java.util.List;

import org.opensearch.migrations.testutils.CloseableLogSetup;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.startsWith;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

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

    @Test
    void jsonOutputIsParsable() throws Exception {
        try (var closeableLogSetup = new CloseableLogSetup(MetadataMigration.class.getName())) {
            var mm = spy(new MetadataMigration());
            doNothing().when(mm).exitWithCode(anyInt());
            mm.run(new String[] {
                "evaluate",
                "--source-host", "http://foo",
                "--target-host", "http://bar",
                "--output", "json"
            });

            var logEvents = closeableLogSetup.getLogEvents();

            assertThat(logEvents, hasSize(1));
            var event = logEvents.get(0);
            var root = new ObjectMapper().readTree(event);

            assertThat("should have an 'errors' array", root.has("errors"), equalTo(true));
            assertThat(root.get("errors").size(), equalTo(2));
            assertThat(root.get("errors").get(0).asText(), equalTo("No source was defined"));
            assertThat(root.get("errors").get(1).asText(), equalTo("No target was defined"));
            assertThat(root.get("errorCode").asInt(), equalTo(888));
            assertThat(root.has("errorMessage"), equalTo(true));
            assertThat(root.get("errorMessage").asText(), startsWith("Unexpected failure:"));
            verify(mm).exitWithCode(888);
        }
    }
}
