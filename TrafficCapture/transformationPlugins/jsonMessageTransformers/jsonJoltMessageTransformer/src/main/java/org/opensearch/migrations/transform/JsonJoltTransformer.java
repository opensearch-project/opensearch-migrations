package org.opensearch.migrations.transform;

import com.bazaarvoice.jolt.Chainr;

import java.util.List;
import java.util.Map;

public class JsonJoltTransformer implements IJsonTransformer {

    Chainr spec;


    public JsonJoltTransformer(List<Object> joltOperationsSpecList) {
        this.spec = Chainr.fromSpec(joltOperationsSpecList);
    }

    public static JsonJoltTransformBuilder newBuilder() {
        return new JsonJoltTransformBuilder();
    }

    @Override
    public Map<String,Object> transformJson(Map<String,Object> incomingJson) {
        return (Map<String,Object>) this.spec.transform(incomingJson);
    }
}
