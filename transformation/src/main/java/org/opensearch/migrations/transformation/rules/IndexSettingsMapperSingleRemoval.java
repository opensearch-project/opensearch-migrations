package org.opensearch.migrations.transformation.rules;

import java.util.Optional;

import org.opensearch.migrations.transformation.CanApplyResult;
import org.opensearch.migrations.transformation.TransformationRule;
import org.opensearch.migrations.transformation.entity.Index;

import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class IndexSettingsMapperSingleRemoval implements TransformationRule<Index> {
    public static final String MAPPINGS_KEY = "mappings";
    public static final String SETTINGS_KEY = "settings";

    public IndexSettingsMapperSingleRemoval() {
    }

    @Override
    public CanApplyResult canApply(final Index index) {
        log.atInfo().setMessage("Checking for single_type of {}").addArgument(index.getRawJson().toPrettyString()).log();
        var settingsNode = index.getRawJson().get(SETTINGS_KEY);

        var singleTypeSetting = Optional.of(settingsNode)
            .map(n -> n.get("index"))
            .map(n -> n.get("mapping"))
            .map(n -> n.get("single_type"));

        return singleTypeSetting.isPresent() ? CanApplyResult.YES : CanApplyResult.NO;
    }

    @Override
    public boolean applyTransformation(final Index index) {
        if (CanApplyResult.YES != canApply(index)) {
            return false;
        }

        var settingsNode = index.getRawJson().get(SETTINGS_KEY);

        var mappingSettings = Optional.of(settingsNode)
            .map(n -> n.get("index"))
            .map(n -> (ObjectNode)n.get("mapping"));
            
        mappingSettings.get().remove("single_type");
        return true;
    }
}
