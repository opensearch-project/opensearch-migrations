package org.opensearch.migrations.bulkload.common.bulk.enums;

import java.util.Arrays;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public enum IndexOpType {
    INDEX("index"),
    CREATE("create");

    @JsonValue
    private final String value;

    @JsonCreator
    public static IndexOpType from(String v) {
        return Arrays.stream(values())
                .filter(e -> e.value.equals(v))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown index operation type: " + v));
    }
}
