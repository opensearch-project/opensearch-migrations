package org.opensearch.migrations.transform;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class JavascriptTransformer implements IJsonTransformer {
    protected ScriptRunner scriptRunner;
    private final String invocationScript;
    private final Function<Object, Object> bindingsProvider;

    public JavascriptTransformer(String initializationScript,
                                 String invocationScript,
                                 Function<Object, Object> bindingsProvider) {
        this.invocationScript = invocationScript;
        this.bindingsProvider = bindingsProvider;
        this.scriptRunner = new ScriptRunner();
        scriptRunner.runScript(initializationScript);
    }

    @SneakyThrows
    @SuppressWarnings("unchecked")
    public CompletableFuture<Object> transformJsonFuture(Object incomingJson) {
        assert incomingJson instanceof Map;
        var incomingJsonMap = (Map<String, Object>) incomingJson;
        for (var kvp : ((Map<String, Object>) bindingsProvider.apply(incomingJsonMap)).entrySet()) {
            scriptRunner.setGlobal(kvp.getKey(), kvp.getValue());
        }
        return scriptRunner.runScriptAsFuture(invocationScript);
    }

    @Override
    @SneakyThrows
    public Object transformJson(Object incomingJson) {
        return transformJsonFuture(incomingJson).get();
    }
}
