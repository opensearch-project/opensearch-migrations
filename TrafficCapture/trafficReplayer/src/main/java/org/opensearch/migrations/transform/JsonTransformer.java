package org.opensearch.migrations.transform;

import com.bazaarvoice.jolt.Chainr;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.Reader;
import java.util.List;

public class JsonTransformer {

    Chainr spec;

//    public JsonTransformer(Reader jsonSpecReader) {
//    }

    public JsonTransformer(List<Object> joltOperationsSpecList) {
        this.spec = Chainr.fromSpec(joltOperationsSpecList);
    }

    public Object transformJson(Object incomingJson) {
        return this.spec.transform(incomingJson);
    }
}
