package org.opensearch.migrations.transform;

import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

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
                .allowAccessAnnotatedBy(HostAccess.Export.class)
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
        var sourceCodeValue = this.polyglotContext.eval(sourceCode);
        if (context != null) {
            var convertedContextObject = convertObject(context, this.polyglotContext);
            this.mainJavascriptTransformFunction = sourceCodeValue.execute(convertedContextObject);
        } else {
            this.mainJavascriptTransformFunction = sourceCodeValue;
        }
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
        return runScriptAsFuture(incomingJson);
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
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            // Avoid S4349
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

    private Value runScript(Value jsCallableObject, Object... args) {
        var convertedArgs = Arrays.stream(args)
                .map(o -> convertObject(o, this.polyglotContext)).toArray();
        // Wrap the call: convert input to native JS via JSON, call transform, stringify result
        // This avoids GraalVM polyglot Map interop issues where Java Maps passed to JS
        // don't properly reflect property assignments back to the Java Map.
        Value wrapperFn = this.polyglotContext.eval("js",
            "(function(fn, arg) { " +
            "  var input = (typeof arg === 'string') ? JSON.parse(arg) : arg; " +
            "  var result = fn(input); " +
            "  return JSON.stringify(result); " +
            "})");
        // Serialize the first arg to JSON string if it's a Map
        Object firstArg = args.length > 0 ? args[0] : null;
        if (firstArg instanceof java.util.Map || firstArg instanceof java.util.List) {
            try {
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                String jsonArg = mapper.writeValueAsString(firstArg);
                var rval = wrapperFn.execute(jsCallableObject, jsonArg);
                log.atTrace().setMessage("rval={}").addArgument(rval).log();
                return rval;
            } catch (Exception e) {
                log.warn("JSON wrapper failed, falling back to direct call", e);
            }
        }
        var rval = jsCallableObject.execute(convertedArgs);
        log.atTrace().setMessage("rval={}").addArgument(rval).log();
        return rval;
    }

    private static Object convertObject(Object o, Context context) {
        return context.asValue(o);
    }

    public <T> CompletableFuture<T> runScriptAsFuture(Object... args) {
        return fromPromise(runScript(mainJavascriptTransformFunction, args));
    }

    @SuppressWarnings("unchecked")
    <T> CompletableFuture<T> fromPromise(Value value) {
        CompletableFuture<T> future = new CompletableFuture<>();
        if (value.canInvokeMember("then")) {
            // It's a Promise - handle with then()
            // https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Promise/then
            value.invokeMember("then",
                (ProxyExecutable) onFulfilledArg -> {
                    T result = (T) jsValueToJavaObject(onFulfilledArg[0]);
                    future.complete(result);
                    return null;
                },
                (ProxyExecutable) failureArgs -> {
                    Throwable error = new RuntimeException(jsValueToJavaObject(failureArgs[0]).toString());
                    future.completeExceptionally(error);
                    return null;
                }
            );
        } else {
            // It's a direct value - complete immediately
            T result = (T) jsValueToJavaObject(value);
            future.complete(result);
        }
        return future;
    }

    private Object jsValueToJavaObject(Value val) {
        if (val.isNull()) {
            return null;
        } else if (val.isString()) {
            return val.asString();
        } else if (val.isNumber()) {
            if (val.fitsInInt()) return val.asInt();
            if (val.fitsInLong()) return val.asLong();
            return val.asDouble();
        } else if (val.isBoolean()) {
            return val.asBoolean();
        } else if (val.hasArrayElements()) {
            var list = new java.util.ArrayList<>();
            for (long i = 0; i < val.getArraySize(); i++) {
                list.add(jsValueToJavaObject(val.getArrayElement(i)));
            }
            return list;
        } else if (val.hasMembers()) {
            // JS object — try JSON.stringify to capture all properties
            try {
                Value jsonStringify = polyglotContext.eval("js", "(function(o) { return JSON.stringify(o); })");
                Value jsonResult = jsonStringify.execute(val);
                if (!jsonResult.isNull()) {
                    String json = jsonResult.asString();
                    if (json != null && !json.equals("{}") && !json.equals("null")) {
                        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                        return mapper.readValue(json, Object.class);
                    }
                }
            } catch (Exception e) {
                log.debug("JSON.stringify failed, falling back to member extraction", e);
            }
            // Fallback: extract members manually
            var map = new java.util.LinkedHashMap<String, Object>();
            for (String key : val.getMemberKeys()) {
                Value member = val.getMember(key);
                map.put(key, jsValueToJavaObject(member));
            }
            return map;
        } else if (val.isHostObject()) {
            return val.asHostObject();
        } else if (val.isProxyObject()) {
            return val.asProxyObject();
        } else {
            return val.as(Object.class);
        }
    }
}
