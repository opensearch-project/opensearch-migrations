package org.opensearch.migrations.metadata;

import java.util.List;

import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class GlobalMetadataCreatorResults {
    private List<String> legacyTemplates;
    private List<String> indexTemplates;
    private List<String> componentTemplates;
}
