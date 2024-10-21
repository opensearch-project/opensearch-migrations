package org.opensearch.migrations.transform;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.transform.JsonCompositePrecondition.CompositeOperation;

import io.burt.jmespath.BaseRuntime;
import io.burt.jmespath.jcf.JcfRuntime;

public class JsonJMESPathPreconditionProvider implements IJsonPreconditionProvider {

    public static final String SCRIPT_KEY = "script";
    private BaseRuntime<Object> adapterRuntime;

    public JsonJMESPathPreconditionProvider() {
        this.adapterRuntime = new JcfRuntime();
    }

    @Override
    public IJsonPrecondition createPrecondition(Object jsonConfig) {
        var transformers = new ArrayList<JsonJMESPathPrecondition>();
        var configs = new ArrayList<Map<String, Object>>();
        try {
            if (jsonConfig instanceof Map) {
                configs.add((Map<String, Object>) jsonConfig);
            } else if (jsonConfig instanceof List) {
                for (var c : (List) jsonConfig) {
                    configs.add((Map<String, Object>) c);
                }
            } else {
                throw new IllegalArgumentException(getConfigUsageStr());
            }
            for (var c : configs) {
                if (c.size() != 1) {
                    throw new IllegalArgumentException(getConfigUsageStr());
                }
                var scriptValue = c.get(SCRIPT_KEY);
                if (!(scriptValue instanceof String)) {
                    throw new IllegalArgumentException(getConfigUsageStr());
                }
                transformers.add(new JsonJMESPathPrecondition(adapterRuntime, (String) scriptValue));
            }
        } catch (ClassCastException e) {
            throw new IllegalArgumentException(getConfigUsageStr(), e);
        }
        return new JsonCompositePrecondition(CompositeOperation.ALL,
            transformers.toArray(IJsonPrecondition[]::new));
    }

    private String getConfigUsageStr() {
        return this.getClass().getName()
            + " expects the incoming configuration "
            + "to be a Map<String,Object> or a List<Map<String,Object>>.  "
            + "Each of the Maps should have one key-value of \"script\": \"...\".  "
            + "Script values should be a fully-formed inlined JsonPath queries encoded as a json value.  "
            + "All of the values within a configuration will be concatenated into one chained precondition.";
    }
}
