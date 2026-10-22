package org.opensearch.migrations.commands;

import java.util.List;

import org.opensearch.migrations.bulkload.version_os_2_11.GlobalMetadataData_OS_2_11;
import org.opensearch.migrations.metadata.CreationResult;
import org.opensearch.migrations.metadata.GlobalMetadataCreator.TemplateSelection;
import org.opensearch.migrations.metadata.GlobalMetadataCreatorResults;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MigrationPlanTest {

    @Test
    void reportsSelectedAndTotalIndexAndTemplateCounts() {
        var root = new ObjectMapper().createObjectNode();
        root.putObject("templates").putObject("legacy-selected");
        root.putObject("index_template").putObject("index_template").putObject("index-selected");
        root.putObject("component_template").putObject("component_template")
            .putObject("component-selected-1");
        root.withObject("/component_template/component_template").putObject("component-selected-2");
        var selectedMetadata = new GlobalMetadataData_OS_2_11(root);
        var skipped = GlobalMetadataCreatorResults.builder()
            .legacyTemplates(results("legacy-skipped-1", "legacy-skipped-2"))
            .indexTemplates(results("index-skipped"))
            .componentTemplates(List.of())
            .build();

        var plan = MigrationPlan.from(new TemplateSelection(selectedMetadata, skipped), 10, 20_000);

        assertEquals(
            "Migration plan: 10 of 20000 indices and 4 of 7 templates will be migrated "
                + "(legacy templates: 1 of 3; index templates: 1 of 2; component templates: 2 of 2).",
            plan.asCliOutput()
        );
    }

    private static List<CreationResult> results(String... names) {
        return java.util.Arrays.stream(names)
            .map(name -> CreationResult.builder().name(name).build())
            .toList();
    }
}
