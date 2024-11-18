package org.opensearch.migrations.transform;

import java.util.Map;

public class TypeMappingSanitizationTransformerProvider implements IJsonTransformerProvider {

    @Override
    public IJsonTransformer createTransformer(Object jsonConfig) {
        try {
            if (jsonConfig instanceof Map) {
                return new TypeMappingsSanitizationTransformer((Map<String, Map<String, String>>) jsonConfig);
            } else {
                throw new IllegalArgumentException(getConfigUsageStr());
            }
        } catch (ClassCastException e) {
            throw new IllegalArgumentException(getConfigUsageStr(), e);
        }
    }

    private String getConfigUsageStr() {
        return this.getClass().getName()
            + " expects the incoming configuration "
            + "to be a Map<String, Map<String, String>>.  " +
            "The top-level key is a source index name, that key's children values are the index's type mappings.  " +
            "The value for the inner keys is the target index name that the " +
            "source type's documents should be written to.";
    }
}
