package org.opensearch.migrations.transform;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class JsonCompositeTransformer implements IJsonTransformer {
    List<IJsonTransformer> jsonTransformerList;

    public JsonCompositeTransformer(IJsonTransformer... jsonTransformers) {
        this.jsonTransformerList = List.of(jsonTransformers);
    }

    public Object transformJson(Object incomingJson) {
        var lastOutput = new AtomicReference<>(incomingJson);
        jsonTransformerList.forEach(t->lastOutput.set(t.transformJson(lastOutput.get())));
        return lastOutput.get();
    }

}
