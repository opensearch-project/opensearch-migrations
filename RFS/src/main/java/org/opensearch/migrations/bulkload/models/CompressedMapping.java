package org.opensearch.migrations.bulkload.models;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class CompressedMapping {
    private String type;
    private byte[] source;
}
