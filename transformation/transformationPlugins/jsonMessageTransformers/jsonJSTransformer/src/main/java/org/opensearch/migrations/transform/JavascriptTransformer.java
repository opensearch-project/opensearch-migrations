package org.opensearch.migrations.transform;

import java.util.concurrent.CompletableFuture;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;

/**
 * Executes JavaScript transformations using GraalJS.
 *
 * <p>Supports both synchronous return values and JavaScript Promises.
 */
@Slf4j
public class JavascriptTransformer extends GraalTransformer {
    private static final String LANGUAGE_ID = "js";

    public JavascriptTransformer(String script, Object bindings) {
        super(LANGUAGE_ID, script, bindings, createContextBuilder());
    }

    private static Context.Builder createContextBuilder() {
        var engine = Engine.newBuilder(LANGUAGE_ID)
            .option("engine.WarnInterpreterOnly", "false")
            .build();
        return Context.newBuilder().engine(engine);
    }

    @SneakyThrows
    public CompletableFuture<Object> transformJsonFuture(Object incomingJson) {
        return fromPromise(
            getMainTransformFunction().execute(getPolyglotContext().asValue(incomingJson))
        );
    }

    @SuppressWarnings("unchecked")
    public <T> CompletableFuture<T> runScriptAsFuture(Object... args) {
        var convertedArgs = java.util.Arrays.stream(args)
            .map(o -> getPolyglotContext().asValue(o)).toArray();
        return fromPromise(getMainTransformFunction().execute(convertedArgs));
    }

    @Override
    @SneakyThrows
    public Object transformJson(Object incomingJson) {
        return transformJsonFuture(incomingJson).get();
    }

    @SuppressWarnings("unchecked")
    <T> CompletableFuture<T> fromPromise(Value value) {
        CompletableFuture<T> future = new CompletableFuture<>();
        if (value.canInvokeMember("then")) {
            value.invokeMember("then",
                (ProxyExecutable) onFulfilledArg -> {
                    T result = (T) valueToJavaObject(onFulfilledArg[0]);
                    future.complete(result);
                    return null;
                },
                (ProxyExecutable) failureArgs -> {
                    Throwable error = new RuntimeException(valueToJavaObject(failureArgs[0]).toString());
                    future.completeExceptionally(error);
                    return null;
                }
            );
        } else {
            T result = (T) valueToJavaObject(value);
            future.complete(result);
        }
        return future;
    }
}
