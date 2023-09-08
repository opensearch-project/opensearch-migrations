package org.opensearch.migrations.transform;

import com.bazaarvoice.jolt.Chainr;

import java.util.List;

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
