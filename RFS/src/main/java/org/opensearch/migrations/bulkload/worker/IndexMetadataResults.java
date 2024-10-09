package org.opensearch.migrations.bulkload.worker;

import java.util.List;

import org.opensearch.migrations.metadata.CreationResult;

import lombok.Builder;
import lombok.Singular;

@Builder
public class IndexMetadataResults {
    @Singular
    private final List<CreationResult> indexes;
    @Singular
    private final List<CreationResult> aliases;

    public List<CreationResult> getIndexes() {
        return indexes == null ? List.of() : indexes;
    }

    public List<CreationResult> getAliases() {
        return aliases == null ? List.of() : aliases;
    }
}
