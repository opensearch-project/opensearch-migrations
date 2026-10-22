package org.opensearch.migrations.commands;

import java.util.List;

import org.opensearch.migrations.metadata.CreationResult;
import org.opensearch.migrations.metadata.GlobalMetadataCreator.TemplateSelection;

record MigrationPlan(
    long selectedIndices,
    long totalIndices,
    long selectedLegacyTemplates,
    long totalLegacyTemplates,
    long selectedIndexTemplates,
    long totalIndexTemplates,
    long selectedComponentTemplates,
    long totalComponentTemplates
) {
    static MigrationPlan from(TemplateSelection selection, long selectedIndices, long totalIndices) {
        var selected = selection.metadata();
        var skipped = selection.skippedResults();
        var selectedLegacy = selected.getTemplates().size();
        var selectedIndex = selected.getIndexTemplates().size();
        var selectedComponent = selected.getComponentTemplates().size();

        return new MigrationPlan(
            selectedIndices,
            totalIndices,
            selectedLegacy,
            selectedLegacy + size(skipped.getLegacyTemplates()),
            selectedIndex,
            selectedIndex + size(skipped.getIndexTemplates()),
            selectedComponent,
            selectedComponent + size(skipped.getComponentTemplates())
        );
    }

    long selectedTemplates() {
        return selectedLegacyTemplates + selectedIndexTemplates + selectedComponentTemplates;
    }

    long totalTemplates() {
        return totalLegacyTemplates + totalIndexTemplates + totalComponentTemplates;
    }

    String asCliOutput() {
        return String.format(
            "Migration plan: %d of %d indices and %d of %d templates will be migrated "
                + "(legacy templates: %d of %d; index templates: %d of %d; component templates: %d of %d).",
            selectedIndices,
            totalIndices,
            selectedTemplates(),
            totalTemplates(),
            selectedLegacyTemplates,
            totalLegacyTemplates,
            selectedIndexTemplates,
            totalIndexTemplates,
            selectedComponentTemplates,
            totalComponentTemplates
        );
    }

    private static int size(List<CreationResult> results) {
        return results == null ? 0 : results.size();
    }
}
