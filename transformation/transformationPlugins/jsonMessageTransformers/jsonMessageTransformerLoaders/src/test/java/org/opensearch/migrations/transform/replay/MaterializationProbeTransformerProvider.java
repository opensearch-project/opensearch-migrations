package org.opensearch.migrations.transform.replay;

import java.util.Map;

import org.opensearch.migrations.transform.ConfigFileValueType;
import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.transform.IJsonTransformerProvider;

public class MaterializationProbeTransformerProvider implements IJsonTransformerProvider {
    private static final Map<String, ConfigFileValueType> VALUE_TYPES = Map.of(
        "jsonValue", ConfigFileValueType.JSON,
        "textValue", ConfigFileValueType.TEXT,
        "bytesValue", ConfigFileValueType.BYTES,
        "base64Value", ConfigFileValueType.BASE64,
        "pathValue", ConfigFileValueType.PATH
    );

    @Override
    public ConfigFileValueType getFileBackedConfigValueType(String configKey) {
        return VALUE_TYPES.getOrDefault(configKey, ConfigFileValueType.TEXT);
    }

    @Override
    public IJsonTransformer createTransformer(Object jsonConfig) {
        return ignored -> jsonConfig;
    }
}
