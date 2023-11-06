package org.opensearch.migrations.transform;

import java.util.Optional;

public class JsonTransformerForOpenSearch23PlusTargetTransformerProvider implements IJsonTransformerProvider {
    @Override
    public IJsonTransformer createTransformer(Optional<String> args) {
        return new JsonTypeMappingTransformer();
    }
}
