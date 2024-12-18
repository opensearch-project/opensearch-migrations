package org.opensearch.migrations.transform;

public class JsonConditionalTransformer implements IJsonTransformer {
    IJsonPredicate jsonPredicate;
    IJsonTransformer jsonTransformer;

    public JsonConditionalTransformer(IJsonPredicate jsonPredicate, IJsonTransformer jsonTransformer) {
        this.jsonPredicate = jsonPredicate;
        this.jsonTransformer = jsonTransformer;
    }

    @Override
    public Object transformJson(Object incomingJson) {
        if (jsonPredicate.test(incomingJson)) {
            return jsonTransformer.transformJson(incomingJson);
        }
        return incomingJson;
    }
}
