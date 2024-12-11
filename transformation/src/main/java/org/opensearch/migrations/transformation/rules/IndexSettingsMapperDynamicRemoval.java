package org.opensearch.migrations.transformation.rules;

import java.util.Optional;

import org.opensearch.migrations.transformation.CanApplyResult;
import org.opensearch.migrations.transformation.TransformationRule;
import org.opensearch.migrations.transformation.entity.Index;

import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class IndexSettingsMapperDynamicRemoval implements TransformationRule<Index> {
    public static final String MAPPINGS_KEY = "mappings";
    public static final String SETTINGS_KEY = "settings";

    public IndexSettingsMapperDynamicRemoval() {
    }

    @Override
    public CanApplyResult canApply(final Index index) {
        var settingsNode = index.getRawJson().get(SETTINGS_KEY);

        var dynamicSetting = Optional.of(settingsNode)
            .map(n -> n.get("index"))
            .map(n -> n.get("mapper"))
            .map(n -> n.get("dynamic"));

        return dynamicSetting.isPresent() ? CanApplyResult.YES : CanApplyResult.NO;
    }

    @Override
    public boolean applyTransformation(final Index index) {
        if (CanApplyResult.YES != canApply(index)) {
            return false;
        }

        var rawJson = index.getRawJson();
        var settingsNode = (ObjectNode) rawJson.get(SETTINGS_KEY);

        var mapperSettings = Optional.of(settingsNode)
            .map(n -> n.get("index"))
            .map(n -> (ObjectNode)n.get("mapper"));
        var dynamicSetting = mapperSettings
            .map(n -> n.get("dynamic"));

        boolean dynamicValueSetTrue = false;
        try {
            dynamicValueSetTrue = Boolean.parseBoolean(dynamicSetting.get().asText()) && true;
        } catch (Exception _e) {
            // Ignore parse exception
        }
        mapperSettings.get().remove("dynamic");

        if (dynamicValueSetTrue) {
            var mappingNode = (ObjectNode) rawJson.get(MAPPINGS_KEY);
            mappingNode.put("dynamic", true);
        }

        return true;
    }
}
