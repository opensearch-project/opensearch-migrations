package org.opensearch.migrations.metadata;

import java.util.List;
import java.util.stream.Stream;

import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class GlobalMetadataCreatorResults {
    private List<CreationResult> legacyTemplates;
    private List<CreationResult> indexTemplates;
    private List<CreationResult> componentTemplates;

    public long fatalIssueCount() {
        return Stream.of(getLegacyTemplates(), getIndexTemplates(), getComponentTemplates())
            .flatMap(List::stream)
            .filter(CreationResult::wasFatal)
            .count();
    }
}
