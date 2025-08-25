package org.opensearch.migrations.bulkload.common.bulk.enums;

import java.util.Arrays;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public enum OperationType {
    INDEX("index"),
    CREATE("create"),
    UPDATE("update"),
    DELETE("delete");

    @JsonValue
    private final String value;

    @JsonCreator
    public static OperationType from(String v) {
        return Arrays.stream(values())
                .filter(e -> e.value.equals(v))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown operation type: " + v));
    }
}
