package org.opensearch.migrations.transform;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class JsonCompositeTransformer implements IJsonTransformer {
    private final List<IJsonTransformer> jsonTransformerList;

    public JsonCompositeTransformer(IJsonTransformer... jsonTransformers) {
        this.jsonTransformerList = List.of(jsonTransformers);
    }

    /** Immutable stages in execution order, for consumers that support native transformation contracts. */
    public List<IJsonTransformer> getTransformers() {
        return jsonTransformerList;
    }

    @Override
    public Object transformJson(Object incomingJson) {
        AtomicReference<Object> lastOutput = new AtomicReference<>(incomingJson);
        jsonTransformerList.forEach(t -> lastOutput.set(t.transformJson(lastOutput.get())));
        return lastOutput.get();
    }

}
