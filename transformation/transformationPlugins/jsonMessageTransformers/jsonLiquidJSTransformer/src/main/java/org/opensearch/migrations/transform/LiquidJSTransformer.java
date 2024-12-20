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
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;

@Slf4j
public class LiquidJSTransformer implements IJsonTransformer {

    ObjectMapper objectMapper = new ObjectMapper();
    private final Context context;
    private final UnaryOperator<Map<String, Object>> bindingsProvider;

    public LiquidJSTransformer(String templateString,
                               UnaryOperator<Map<String, Object>> bindingsProvider) throws IOException {
        this.bindingsProvider = bindingsProvider;
        this.context = Context.newBuilder("js")
            .allowAllAccess(true)
            .build();

        context.getBindings("js").putMember("userProvidedTemplate", templateString);

        // Load LiquidJS javascript script
        var liquidJsContent = Resources.toString(Resources.getResource("liquid.min.js"), StandardCharsets.UTF_8);

        // First evaluate the library
        context.eval("js", liquidJsContent);

        // Then use the global Liquid object that was created
        context.eval("js", "" +
            "const engine = new liquidjs.Liquid();"
            + "var parsedTemplate = engine.parse(userProvidedTemplate);"
        );
    }

    public CompletableFuture<Object> transformJsonFuture(Map<String, Object> incomingJson) {
        var userBindings = bindingsProvider.apply(incomingJson);
        context.getBindings("js").putMember("incomingJson", ProxyObject.fromMap(userBindings));
        Value promise = context.eval("js", "engine.render(parsedTemplate, incomingJson);");
        return fromPromise(promise);
    }

    @SneakyThrows
    public String runJavascript(Map<String, Object> incomingJson) {
        context.getBindings("js").putMember("incomingJson", ProxyObject.fromMap(incomingJson));
        var resultPromise = context.eval("js", "" +
            "JSON.stringify(incomingJson);\n");
        var resultStr = (String) fromPromise(resultPromise).get();
        log.atTrace().setMessage("resultStr={}").addArgument(resultStr).log();
        return resultStr;
    }

    @SneakyThrows
    @Override
    public Map<String, Object> transformJson(Map<String, Object> incomingJson) {
        var resultStr = (String) transformJsonFuture(incomingJson).get();
        log.atTrace().setMessage("resultStr={}").addArgument(resultStr).log();
        var parsedObj = (Map<String,Object>) objectMapper.readValue(resultStr, LinkedHashMap.class);
        return parsedObj;
    }

    @SuppressWarnings("Unchecked")
    private static <T> CompletableFuture<T> fromPromise(Value value) {
        CompletableFuture<T> future = new CompletableFuture<>();

        if (value.canInvokeMember("then")) {
            // It's a Promise - handle with then()
            // https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Promise/then
            value.invokeMember("then",
                (ProxyExecutable) onFulfilledArg -> {
                    T result = (T) onFulfilledArg[0].as(Object.class);
                    future.complete(result);
                    return null;
                },
                (ProxyExecutable) failureArgs -> {
                    Throwable error = new RuntimeException(failureArgs[0].toString());
                    future.completeExceptionally(error);
                    return null;
                }
            );
        } else {
            // It's a direct value - complete immediately
            T result = (T) value.as(Object.class);
            future.complete(result);
        }
        return future;
    }
}
