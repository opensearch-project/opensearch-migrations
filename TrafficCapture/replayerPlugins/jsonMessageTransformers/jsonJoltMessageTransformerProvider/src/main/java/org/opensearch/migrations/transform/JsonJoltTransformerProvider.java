package org.opensearch.migrations.transform;

import java.util.Optional;

public class JsonJoltTransformerProvider implements IJsonTransformerProvider {
    @Override
    public IJsonTransformer createTransformer(Object jsonConfig) {
        var builder = JsonJoltTransformer.newBuilder();
        //builder.addOperationObject()
        return builder.build();
    }
}
