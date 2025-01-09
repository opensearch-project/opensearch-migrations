package org.opensearch.migrations.transform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.UnaryOperator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.io.Resources;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LiquidJSTransformer implements IJsonTransformer {

    private final UnaryOperator<Object> bindingsProvider;
    protected ScriptRunner scriptRunner;
    ObjectMapper objectMapper = new ObjectMapper();

    public LiquidJSTransformer(String templateString,
                               UnaryOperator<Object> bindingsProvider) throws IOException {
        this.bindingsProvider = bindingsProvider;
        scriptRunner = new ScriptRunner();

        var liquidJsContent = // Load LiquidJS javascript script
            Resources.toString(Resources.getResource("liquid.min.js"), StandardCharsets.UTF_8);
        scriptRunner.runScript(liquidJsContent);

        scriptRunner.setGlobal("userProvidedTemplate", templateString);
        scriptRunner.runScript("" + // initialize, including the parsed template
            "const engine = new liquidjs.Liquid();\n" +
            "var parsedTemplate = engine.parse(userProvidedTemplate);"
        );
    }

    public CompletableFuture<Object> transformJsonFuture(Object incomingJson) {
        scriptRunner.setGlobal("incomingJson", bindingsProvider.apply(incomingJson));
        return scriptRunner.runScriptAsFuture("engine.render(parsedTemplate, incomingJson);");
    }

    @SneakyThrows
    @Override
    @SuppressWarnings("unchecked")
    public Object transformJson(Object incomingJson) {
        var resultStr = (String) transformJsonFuture(incomingJson).get();
        log.atTrace().setMessage("resultStr={}").addArgument(resultStr).log();
        var parsedObj = (Map<String,Object>) objectMapper.readValue(resultStr, LinkedHashMap.class);
        return parsedObj;
    }
}
