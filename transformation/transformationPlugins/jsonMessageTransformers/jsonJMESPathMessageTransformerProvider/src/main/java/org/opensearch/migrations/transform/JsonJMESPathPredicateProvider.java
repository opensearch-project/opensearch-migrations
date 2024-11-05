package org.opensearch.migrations.transform;

import java.util.Map;

import io.burt.jmespath.BaseRuntime;
import io.burt.jmespath.jcf.JcfRuntime;

public class JsonJMESPathPredicateProvider implements IJsonPredicateProvider {

    public static final String SCRIPT_KEY = "script";
    private BaseRuntime<Object> adapterRuntime;

    public JsonJMESPathPredicateProvider() {
        this.adapterRuntime = new JcfRuntime();
    }

    @Override
    public IJsonPredicate createPredicate(Object jsonConfig) {
        try {
            if (jsonConfig instanceof Map) {
                @SuppressWarnings("unchecked")
                var jsonConfigMap = (Map<String, Object>) jsonConfig;
                if (jsonConfigMap.size() != 1) {
                    throw new IllegalArgumentException(getConfigUsageStr());
                }
                var scriptValue = jsonConfigMap.get(SCRIPT_KEY);
                if (!(scriptValue instanceof String)) {
                    throw new IllegalArgumentException(getConfigUsageStr());
                }
                return new JsonJMESPathPredicate(adapterRuntime, (String) scriptValue);
            }
            throw new IllegalArgumentException(getConfigUsageStr());
        } catch (ClassCastException e) {
            throw new IllegalArgumentException(getConfigUsageStr(), e);
        }
    }

    private String getConfigUsageStr() {
        return this.getClass().getName()
            + " expects the incoming configuration "
            + "to be a Map<String,Object>.  "
            + "Each of the Maps should have one key-value of \"script\": \"...\".  "
            + "Script values should be a fully-formed inlined JsonPath queries.";
    }
}
