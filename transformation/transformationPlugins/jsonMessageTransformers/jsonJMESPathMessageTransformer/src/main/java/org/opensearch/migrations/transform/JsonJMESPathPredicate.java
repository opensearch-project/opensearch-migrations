package org.opensearch.migrations.transform;


import io.burt.jmespath.BaseRuntime;
import io.burt.jmespath.Expression;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class JsonJMESPathPredicate implements IJsonPredicate {

    Expression<Object> expression;

    public JsonJMESPathPredicate(BaseRuntime<Object> runtime, String script) {
        this.expression = runtime.compile(script);
    }

    @Override
    public boolean test(Object incomingJson) {
        var output = expression.search(incomingJson);
        log.atDebug().setMessage("output={}").addArgument(output).log();
        return (Boolean) output;
    }
}
