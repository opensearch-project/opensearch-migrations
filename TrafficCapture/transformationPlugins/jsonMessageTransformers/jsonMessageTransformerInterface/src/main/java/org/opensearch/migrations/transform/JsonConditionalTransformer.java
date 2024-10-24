package org.opensearch.migrations.transform;

import java.util.Map;

public class JsonConditionalTransformer implements IJsonTransformer {
    IJsonPredicate jsonPredicate;
    IJsonTransformer jsonTransformer;

    public JsonConditionalTransformer(IJsonPredicate jsonPredicate, IJsonTransformer jsonTransformer) {
        this.jsonPredicate = jsonPredicate;
        this.jsonTransformer = jsonTransformer;
    }

    @Override
    public Map<String, Object> transformJson(Map<String, Object> incomingJson) {
        if (jsonPredicate.test(incomingJson)) {
            return jsonTransformer.transformJson(incomingJson);
        }
        return incomingJson;
    }
}
