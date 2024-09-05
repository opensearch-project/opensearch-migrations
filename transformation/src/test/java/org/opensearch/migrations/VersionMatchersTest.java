package org.opensearch.migrations;

import java.util.List;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

public class VersionMatchersTest {

    private void testPredicate(Predicate<Version> predicate, String matcherName, List<String> expectMatches, List<String> expectDoesNotMatch) {
        expectMatches.forEach(v -> {
            assertThat(v + " should be matched by " + matcherName, predicate.test(Version.fromString(v)), equalTo(true));
        });

        expectDoesNotMatch.forEach(v -> {
            assertThat(v + " should NOT be matched by " + matcherName, predicate.test(Version.fromString(v)), equalTo(false));
        });
    }

    @Test
    void isES_6_8Test() {
        testPredicate(
            VersionMatchers.isES_6_8,
            "isES_6_8",
            List.of("ES 6.8", "ES 6.8.23"),
            List.of("ES 6.7", "ES 6.9", "OS 1.3")
        );
    }

    @Test
    void isES_7_XTest() {
        testPredicate(
            VersionMatchers.isES_7_X,
            "isES_7_X",
            List.of("ES 7", "ES 7.1"),
            List.of("ES 6.7", "ES 8.0", "OS 7.5")
        );
    }

    @Test
    void equalOrGreaterThanES_7_10Test() {
        testPredicate(
            VersionMatchers.equalOrGreaterThanES_7_10,
            "equalOrGreaterThanES_7_10",
            List.of("ES 7.10", "ES 7.11.1"),
            List.of("ES 7", "ES 7.1", "ES 7.9", "OS 7.10")
        );
    }

    @Test
    void isOS_1_XTest() {
        testPredicate(
            VersionMatchers.isOS_1_X,
            "isOS_1_X",
            List.of("OS 1.0", "OS 1.3"),
            List.of("OS 0.9.0", "OS 2", "ES 6.9")
        );

    }

    @Test
    void isOS_2_XTest() {
        testPredicate(
            VersionMatchers.isOS_2_X,
            "isOS_2_X",
            List.of("OS 2.0", "OS 2.15"),
            List.of("OS 1.3", "OS 3.0", "ES 2.3")
        );
    }
}
