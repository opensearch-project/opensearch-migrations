package org.opensearch.migrations;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.opensearch.migrations.VersionMatchersTest.testPredicate;

public class UnboundVersionMatchersTest {

    @Test
    void testAnyOS() {
        testPredicate(
            UnboundVersionMatchers.anyOS,
            "anyOs",
            List.of("OS 0.0", "OS 1.3", "OS 4", "OS 999.10"),
            List.of("ES 6.8", "ES 6.8.23", "ES 6.9", "ES 2.1")
        );
    }

    @Test
    void testAnyES() {
        testPredicate(
            UnboundVersionMatchers.anyES,
            "anyES",
            List.of("ES 6.8", "ES 6.8.23", "ES 6.9", "ES 2.1", "ES 9999"),
            List.of("OS 0.0", "OS 1.3", "OS 4", "OS 999.10")
        );
    }

    @Test
    void testIsBelowES_6_X() {
        testPredicate(
            UnboundVersionMatchers.isBelowES_6_X,
            "isBelowES_6_X",
            List.of("ES 5.9999.9999", "ES 4.8.23", "ES 0.0", "ES 2.1"),
            List.of("ES 6", "ES 10", "OS 0.0", "OS 1.3", "OS 4", "OS 999.10")
        );
    }

    @Test
    void testIsBelowES_7_X() {
        testPredicate(
            UnboundVersionMatchers.isBelowES_7_X,
            "isBelowES_7_X",
            List.of("ES 6.9999.9999", "ES 4.8.23", "ES 0.0", "ES 2.1"),
            List.of("ES 7", "ES 10", "OS 0.0", "OS 1.3", "OS 4", "OS 999.10")
        );
    }

    @Test
    void testIsGreaterOrEqualES_6_X() {
        testPredicate(
            UnboundVersionMatchers.isGreaterOrEqualES_6_X,
            "isGreaterOrEqualES_6_X",
            List.of("ES 6.0", "ES 7", "ES 10"),
            List.of("ES 5.9999.9999", "ES 4.8.23", "ES 0.0", "ES 2.1", "OS 0.0", "OS 1.3", "OS 4", "OS 999.10")
        );
    }

    @Test
    void testIsGreaterOrEqualES_7_X() {
        testPredicate(
            UnboundVersionMatchers.isGreaterOrEqualES_7_X,
            "isGreaterOrEqualES_7_X",
            List.of("ES 7.0", "ES 7", "ES 10"),
            List.of("ES 6.0", "ES 5.9999.9999", "ES 4.8.23", "ES 0.0", "ES 2.1", "OS 0.0", "OS 1.3", "OS 4", "OS 999.10")
        );
    }

}
