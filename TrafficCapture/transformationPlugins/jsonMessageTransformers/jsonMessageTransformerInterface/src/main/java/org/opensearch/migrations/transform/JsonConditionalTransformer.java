package org.opensearch.migrations.transform;

import java.util.Map;

public class JsonConditionalTransformer implements IJsonTransformer {
    IJsonPrecondition jsonPrecondition;
    IJsonTransformer jsonTransformer;

    public JsonConditionalTransformer(IJsonPrecondition jsonPrecondition, IJsonTransformer jsonTransformer) {
        this.jsonPrecondition = jsonPrecondition;
        this.jsonTransformer = jsonTransformer;
    }

    @Override
    public Map<String, Object> transformJson(Map<String, Object> incomingJson) {
        if (jsonPrecondition.evaluatePrecondition(incomingJson)) {
            return jsonTransformer.transformJson(incomingJson);
        }
        return incomingJson;
    }
}
