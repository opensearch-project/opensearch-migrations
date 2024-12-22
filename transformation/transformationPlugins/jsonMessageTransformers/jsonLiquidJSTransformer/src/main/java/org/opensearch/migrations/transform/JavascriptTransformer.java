package org.opensearch.migrations.transform;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.UnaryOperator;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class JavascriptTransformer implements IJsonTransformer {
    protected ScriptRunner scriptRunner;
    private final String invocationScript;
    private final UnaryOperator<Map<String, Object>> bindingsProvider;

    public JavascriptTransformer(String initializationScript,
                                 String invocationScript,
                                 UnaryOperator<Map<String, Object>> bindingsProvider) {
        this.invocationScript = invocationScript;
        this.bindingsProvider = bindingsProvider;
        this.scriptRunner = new ScriptRunner();
        scriptRunner.runScript(initializationScript);
    }

    @SneakyThrows
    public CompletableFuture<Object> transformJsonFuture(Map<String, Object> incomingJson) {
        for (var kvp : bindingsProvider.apply(incomingJson).entrySet()) {
            scriptRunner.setGlobal(kvp.getKey(), kvp.getValue());
        }
        return scriptRunner.runScriptAsFuture(invocationScript);
    }

    @Override
    @SneakyThrows
    public Map<String, Object> transformJson(Map<String, Object> incomingJson) {
        return (Map<String, Object>) transformJsonFuture(incomingJson).get();
    }
}
