package org.opensearch.migrations.transform;


import io.burt.jmespath.BaseRuntime;
import io.burt.jmespath.Expression;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;


@Slf4j
public class JsonJMESPathTransformer implements IJsonTransformer {

    Expression<Object> expression;

    public JsonJMESPathTransformer(BaseRuntime<Object> runtime, String script) {
        this.expression = runtime.compile(script);
    }

    @Override
    public Map<String,Object> transformJson(Map<String,Object> incomingJson) {
        var output = expression.search(incomingJson);
        log.info("output="+output);
        return (Map<String,Object>) output;
    }
}
