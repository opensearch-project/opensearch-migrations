package org.opensearch.migrations.bulkload.common;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class S3AddressingStyleTest {

    private static boolean resolve(boolean defaultPathStyle, String value) {
        return S3AddressingStyle.forcePathStyle(defaultPathStyle, Map.of(S3AddressingStyle.ENV_VAR, value)::get);
    }

    @Test
    void unsetKeepsDefault() {
        assertEquals(true, S3AddressingStyle.forcePathStyle(true, Map.<String, String>of()::get));
        assertEquals(false, S3AddressingStyle.forcePathStyle(false, Map.<String, String>of()::get));
    }

    @ParameterizedTest
    @ValueSource(strings = {"path", "PATH", " Path "})
    void pathForcesPathStyle(String value) {
        assertEquals(true, resolve(false, value));
        assertEquals(true, resolve(true, value));
    }

    @ParameterizedTest
    @ValueSource(strings = {"virtual", "VIRTUAL", " Virtual\n"})
    void virtualForcesVirtualHosted(String value) {
        assertEquals(false, resolve(true, value));
        assertEquals(false, resolve(false, value));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "auto", "AUTO", "bogus"})
    void autoBlankOrUnknownKeepsDefault(String value) {
        assertEquals(true, resolve(true, value));
        assertEquals(false, resolve(false, value));
    }
}
