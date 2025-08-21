package org.opensearch.migrations.bulkload.common.enums;

import java.util.Arrays;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Enum representing the operation type for an RFS document.
 * This is used to distinguish between documents that should be indexed
 * versus documents that represent deletions.
 */
@AllArgsConstructor
@Getter
public enum RfsDocumentOperation {
    /**
     * Document should be indexed (normal operation)
     */
    INDEX("index"),
    
    /**
     * Document represents a deletion operation
     */
    DELETE("delete");

    @JsonValue
    private final String value;

    @JsonCreator
    public static RfsDocumentOperation from(String v) {
        return Arrays.stream(values())
                .filter(e -> e.value.equals(v))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown RFS document operation: " + v));
    }
}
