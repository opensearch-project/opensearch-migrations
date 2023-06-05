package org.opensearch.migrations.transform;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class CompositeJsonTransformer implements JsonTransformer {
    List<JsonTransformer> jsonTransformerList;

    public CompositeJsonTransformer(JsonTransformer... jsonTransformers) {
        this.jsonTransformerList = List.of(jsonTransformers);
    }

    public Object transformJson(Object incomingJson) {
        AtomicReference lastOutput = new AtomicReference(incomingJson);
        jsonTransformerList.forEach(t->lastOutput.set(t.transformJson(lastOutput.get())));
        return lastOutput.get();
    }

}
