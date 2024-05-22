package org.opensearch.migrations.transform;

public class JsonTransformerForOpenSearch23PlusTargetTransformerProvider implements IJsonTransformerProvider {
    @Override
    public IJsonTransformer createTransformer(Object jsonConfig) {
        return new JsonTypeMappingTransformer();
    }
}
