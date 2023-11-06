package org.opensearch.migrations.transform;

import java.util.Optional;

public class JsonTransformerForOpenSearch23PlusTargetTransformerProvider implements IJsonTransformerProvider {
    @Override
    public IJsonTransformer createTransformer(Object jsonConfig) {
        return new JsonTypeMappingTransformer();
    }
}
