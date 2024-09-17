package com.rfs.worker;

import java.util.List;

import lombok.Builder;
import lombok.Data;
import lombok.Singular;

@Builder
@Data
public class IndexMetadataResults {
    @Singular
    private final List<String> indexNames;
    @Singular
    private final List<String> aliases;
}
