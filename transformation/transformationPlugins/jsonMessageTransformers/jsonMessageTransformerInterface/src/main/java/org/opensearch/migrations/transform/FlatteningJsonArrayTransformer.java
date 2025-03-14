package org.opensearch.migrations.transform;

import java.util.List;

public class FlatteningJsonArrayTransformer implements IJsonTransformer {
    IJsonTransformer jsonTransformer;

    public FlatteningJsonArrayTransformer(IJsonTransformer jsonTransformer) {
        this.jsonTransformer = jsonTransformer;
    }

    @Override
    public Object transformJson(Object incomingJson) {
        if (incomingJson instanceof List<?>) {
            return ((List<?>) incomingJson).stream()
                    .map(jsonTransformer::transformJson)
                    .toList();
        }
        return jsonTransformer.transformJson(incomingJson);
    }
}
