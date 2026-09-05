package org.opensearch.migrations;

import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExceptionTypeAllowlistTest {
    @Test
    void matchingUsesOneSharedNormalizationRule() {
        var allowlist = new ExceptionTypeAllowlist(Set.of(" Version_Conflict_Engine_Exception "));

        assertTrue(allowlist.isAllowed("version_conflict_engine_exception"));
        assertTrue(allowlist.isAllowed(" VERSION_CONFLICT_ENGINE_EXCEPTION "));
        assertFalse(allowlist.isAllowed("mapper_parsing_exception"));
        assertFalse(allowlist.isAllowed(null));
    }

    @Test
    void blankEntriesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ExceptionTypeAllowlist(Set.of(" ")));
    }
}
