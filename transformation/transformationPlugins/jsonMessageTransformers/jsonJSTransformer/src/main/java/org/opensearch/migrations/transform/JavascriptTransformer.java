package org.opensearch.migrations.transform;

import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.opensearch.migrations.transform.jsProxyObjects.ArrayProxyObject;
import org.opensearch.migrations.transform.jsProxyObjects.ListProxyArray;
import org.opensearch.migrations.transform.jsProxyObjects.MapProxyObject;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

/**
 * The {@code JavascriptTransformer} class provides functionality to execute JavaScript transformations
 * on JSON-like objects using the GraalVM Polyglot API.
 *
 * <p><strong>Important:</strong> This class is <b>not thread-safe</b>. Concurrent access to instances of this class
 * may result in unpredictable behavior or errors. Each thread should use its own instance of {@code JavascriptTransformer}.
 *
 * <p><strong>Thread Safety:</strong>
 * The {@code JavascriptTransformer} relies on an underlying {@code Context} and JavaScript engine
 * that are not designed to handle concurrent operations. Ensure that instances are not shared
 * across multiple threads or synchronize access externally if required.
 */
@Slf4j
public class JavascriptTransformer implements IJsonTransformer {
    private static final String JS_TRANSFORM_LOGGER_NAME = "JavascriptTransformer";
    private Value mainJavascriptTransformFunction;

    private final Context polyglotContext;
    private final OutputStream infoStream;
    private final OutputStream errorStream;

    public JavascriptTransformer(String script,
                                 Object context) {
        var sourceCode = Source.create("js", script);
        var engine = Engine.newBuilder("js")
            .option("engine.WarnInterpreterOnly", "false")
            .build();
        var builder = Context.newBuilder()
            .engine(engine)
            .allowHostAccess(HostAccess.newBuilder()
                .allowArrayAccess(true)
                .allowMapAccess(true)
                .allowListAccess(true)
                .allowIterableAccess(true)
                .allowBufferAccess(true) // Support replayer binary data buffer
                .build());
            var jsLogger = LoggerFactory.getLogger(JS_TRANSFORM_LOGGER_NAME);
            this.infoStream = new LoggingOutputStream(jsLogger, Level.INFO);
            this.errorStream = new LoggingOutputStream(jsLogger, Level.ERROR);
            this.polyglotContext = builder
                .out(infoStream)
                .err(errorStream)
                .build();
            this.mainJavascriptTransformFunction = this.polyglotContext.eval(sourceCode).execute(context);
        }

    @Override
    public void close() throws Exception {
        this.mainJavascriptTransformFunction = null;
        this.polyglotContext.close();
        this.infoStream.close();
        this.errorStream.close();
    }

    @SneakyThrows
    public CompletableFuture<Object> transformJsonFuture(Object incomingJson) {
        return runScriptAsFuture(mainJavascriptTransformFunction, incomingJson);
    }

    @Override
    @SneakyThrows
    public Object transformJson(Object incomingJson) {
        return transformJsonFuture(incomingJson).get();
    }

    public static class LoggingOutputStream extends FilterOutputStream {
        private final Logger logger;
        private final Level level;

        public LoggingOutputStream(Logger logger, Level level) {
            super(new ByteArrayOutputStream());
            this.logger = logger;
            this.level = level;
        }

        @Override
        public void flush() throws IOException {
            super.flush();
            if (out instanceof ByteArrayOutputStream) {
                // Note: We cannot replace 'baos' with pattern variable until MIGRATIONS-2344
                var baos = (ByteArrayOutputStream) out;
                out.flush();
                if (baos.size() > 0) {
                    logger.atLevel(level).setMessage("{}")
                        .addArgument(() -> baos.toString(StandardCharsets.UTF_8))
                        .log();
                    baos.reset();
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    static Value runScript(Value jsCallableObject, Object... args) {
        var convertedArgs = Arrays.stream(args)
            .map(o -> {
                if (o instanceof Map<?, ?>) {
                    return new MapProxyObject((Map<String, Object>) o);
                } else if (o instanceof List<?>) {
                    return new ListProxyArray((List<Object>) o);
                } else if (o instanceof Object[]) {
                    return new ArrayProxyObject((Object[]) o);
                } else if (isPrimitiveOrWrapper(o)) {
                    return o;
                }
                throw new IllegalArgumentException("Unsupported argument type: " + o.getClass());
            }).toArray();
        var rval = jsCallableObject.execute(convertedArgs);
        log.atTrace().setMessage("rval={}").addArgument(rval).log();
        return rval;
    }

    private static boolean isPrimitiveOrWrapper(Object o) {
        return o instanceof Integer || o instanceof Double || o instanceof Boolean
            || o instanceof Character || o instanceof Byte || o instanceof Short
            || o instanceof Float || o instanceof Long || o == null;
    }

    static <T> CompletableFuture<T> runScriptAsFuture(Value jsCallableObject, Object... args) {
        return fromPromise(runScript(jsCallableObject, args));
    }

    @SuppressWarnings("Unchecked")
    static <T> CompletableFuture<T> fromPromise(Value value) {
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
