package org.opensearch.migrations.bulkload.common.bulk.enums;

import java.util.Arrays;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public enum SchemaVersion {
    RFS_OPENSEARCH_BULK_V1("rfs-opensearch-bulk-v1");

    @JsonValue
    private final String value;

    @JsonCreator
    public static SchemaVersion from(String v) {
        return Arrays.stream(values())
                .filter(e -> e.value.equals(v))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown schema version: " + v));
    }
}
