package org.opensearch.migrations.transform;

public class JsonTransformerForOpenSearch23PlusTargetTransformerProvider implements IJsonTransformerProvider {
    @Override
    public IJsonTransformerFactory createTransformerFactory(String[] args) {
        return () -> new JsonTypeMappingTransformer();
    }
}
