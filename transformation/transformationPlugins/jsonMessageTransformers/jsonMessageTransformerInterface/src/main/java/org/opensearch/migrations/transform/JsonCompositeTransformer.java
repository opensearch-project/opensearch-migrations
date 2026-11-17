package org.opensearch.migrations.transform;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class JsonCompositeTransformer implements IJsonTransformer {
    private final List<IJsonTransformer> jsonTransformerList;

    public JsonCompositeTransformer(IJsonTransformer... jsonTransformers) {
        this.jsonTransformerList = List.of(jsonTransformers);
    }

    @Override
    public <T> Optional<List<T>> getNativeStages(Class<T> nativeType) {
        // A subclass may add behavior in transformJson; it must explicitly expose
        // a native contract rather than having that behavior bypassed.
        if (getClass() != JsonCompositeTransformer.class) {
            return IJsonTransformer.super.getNativeStages(nativeType);
        }
        var result = new ArrayList<T>();
        for (var transformer : jsonTransformerList) {
            var stages = transformer.getNativeStages(nativeType);
            if (stages.isEmpty()) {
                return Optional.empty();
            }
            result.addAll(stages.get());
        }
        return Optional.of(List.copyOf(result));
    }

    @Override
    public Object transformJson(Object incomingJson) {
        for (var transformer : jsonTransformerList) {
            incomingJson = transformer.transformJson(incomingJson);
        }
        return incomingJson;
    }

}
