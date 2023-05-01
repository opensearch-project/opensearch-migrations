package org.opensearch.migrations.transform;

import com.bazaarvoice.jolt.Chainr;

import java.util.List;

public class JsonTransformer {

    Chainr spec;


    public JsonTransformer(List<Object> joltOperationsSpecList) {
        this.spec = Chainr.fromSpec(joltOperationsSpecList);
    }

    public static JsonTransformBuilder newBuilder() {
        return new JsonTransformBuilder();
    }

    public Object transformJson(Object incomingJson) {
        return this.spec.transform(incomingJson);
    }
}
