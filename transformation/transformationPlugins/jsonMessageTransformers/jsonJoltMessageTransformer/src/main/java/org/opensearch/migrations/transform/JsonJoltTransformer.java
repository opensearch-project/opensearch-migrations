package org.opensearch.migrations.transform;

import java.util.List;

import com.bazaarvoice.jolt.Chainr;

public class JsonJoltTransformer implements IJsonTransformer {

    Chainr spec;

    public JsonJoltTransformer(List<Object> joltOperationsSpecList) {
        this.spec = Chainr.fromSpec(joltOperationsSpecList);
    }

    public static JsonJoltTransformBuilder newBuilder() {
        return new JsonJoltTransformBuilder();
    }

    @Override
    public Object transformJson(Object incomingJson) {
        return this.spec.transform(incomingJson);
    }
}
