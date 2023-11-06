package org.opensearch.migrations.transform;

import java.util.Optional;

public class JsonJoltTransformerProvider implements IJsonTransformerProvider {
    @Override
    public IJsonTransformer createTransformer(Optional<String> config) {
        var builder = JsonJoltTransformer.newBuilder();
        //builder.addOperationObject()
    }
}
