package org.opensearch.migrations.bulkload.worker;

import java.util.List;

import org.opensearch.migrations.metadata.CreationResult;

import lombok.Builder;
import lombok.Data;
import lombok.Singular;

@Builder
@Data
public class IndexMetadataResults {
    @Singular
    private final List<CreationResult> indexNames;
    @Singular
    private final List<CreationResult> aliases;
}
