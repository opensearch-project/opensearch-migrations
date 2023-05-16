package org.opensearch.migrations.transform;

import com.bazaarvoice.jolt.Chainr;

import java.util.List;

public class JoltJsonTransformer implements JsonTransformer {

    Chainr spec;


    public JoltJsonTransformer(List<Object> joltOperationsSpecList) {
        this.spec = Chainr.fromSpec(joltOperationsSpecList);
    }

    public static JoltJsonTransformBuilder newBuilder() {
        return new JoltJsonTransformBuilder();
    }

    @Override
    public Object transformJson(Object incomingJson) {
        return this.spec.transform(incomingJson);
    }
}
