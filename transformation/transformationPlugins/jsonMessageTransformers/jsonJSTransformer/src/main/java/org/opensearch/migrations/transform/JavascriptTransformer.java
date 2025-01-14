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
import java.util.function.Function;

import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyArray;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

@Slf4j
public class JavascriptTransformer implements IJsonTransformer {
    private final String JS_TRANSFORM_LOGGER_NAME = "JavascriptTransformer";
    private final Function<Object, Object> bindingsProvider;
    private Value javascriptTransform;

    private final Context context;
    private final OutputStream infoStream;
    private final OutputStream errorStream;

    public JavascriptTransformer(String script,
                                 Function<Object, Object> bindingsProvider) {
        this.bindingsProvider = bindingsProvider;
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
            // Consider closing the context and streams
            var jsLogger = LoggerFactory.getLogger(JS_TRANSFORM_LOGGER_NAME);
            this.infoStream = new LoggingOutputStream(jsLogger, Level.INFO);
            this.errorStream = new LoggingOutputStream(jsLogger, Level.ERROR);
            this.context = builder
                .out(infoStream)
                .err(errorStream)
                .build();
            this.javascriptTransform = context.eval(sourceCode);
        }

    @Override
    public void close() throws Exception {
        this.javascriptTransform = null;
        this.context.close();
        this.infoStream.close();
        this.errorStream.close();
    }

    @SneakyThrows
    public CompletableFuture<Object> transformJsonFuture(Object incomingJson) {
        return runScriptAsFuture(javascriptTransform, incomingJson, bindingsProvider.apply(incomingJson));
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
            if (out instanceof ByteArrayOutputStream) {
                // Note: We cannot replace 'baos' with pattern variable until MIGRATIONS-2344
                var baos = (ByteArrayOutputStream) out;
                logger.atLevel(level).setMessage("{}")
                    .addArgument(() -> baos.toString(StandardCharsets.UTF_8))
                    .log();
                baos.reset();
            }
            super.flush();
        }
    }

    @Getter
    public static class MapProxyObject implements ProxyObject {

        private final Map<String, Object> map;

        public MapProxyObject(Map<String, Object> map) {
            this.map = map;
        }

        @Override
        public void putMember(String key, Value value) {
            map.put(key, value.isHostObject() ? value.asHostObject() : value);
        }

        @Override
        public boolean hasMember(String key) {
            return map.containsKey(key);
        }

        @Override
        public Object getMemberKeys() {
            return map.keySet().toArray();
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object getMember(String key) {
            Object v = map.get(key);

            if (v instanceof Map) {
                // Proxy nested Maps
                return new MapProxyObject((Map<String, Object>) v);

            } else if (v instanceof List) {
                // Proxy Lists
                return new ListProxyArray((List<Object>) v);

            } else if (v != null && v.getClass().isArray()) {
                // Proxy Arrays
                return new ArrayProxyObject((Object[]) v);

            } else {
                return v;
            }
        }

        @Override
        public boolean removeMember(String key) {
            return map.remove(key) != null;
        }
    }

    /**
     * Proxies a List so Graal sees it as a Java array-like object.
     */
    public static class ListProxyArray implements ProxyArray {

        private final List<Object> list;

        public ListProxyArray(List<Object> list) {
            this.list = list;
        }

        @Override
        public long getSize() {
            return list.size();
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object get(long index) {
            Object element = list.get((int) index);
            if (element instanceof Map) {
                return new MapProxyObject((Map<String, Object>) element);
            } else if (element instanceof List) {
                return new ListProxyArray((List<Object>) element);
            } else if (element != null && element.getClass().isArray()) {
                return new ArrayProxyObject((Object[]) element);
            } else {
                return element;
            }
        }

        @Override
        public void set(long index, Value value) {
            // We must handle both direct values and host objects
            // Depending on your design, you might want to treat them differently
            // e.g. unwrapping only if isHostObject
            list.set((int) index, value.isHostObject() ? value.asHostObject() : value);
        }
    }

    /**
     * Proxies an actual Java array (T[]) so Graal sees it as an array-like object.
     */
    public static class ArrayProxyObject implements ProxyArray {

        private final Object[] array;

        public ArrayProxyObject(Object[] array) {
            this.array = array;
        }

        @Override
        public long getSize() {
            return array.length;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object get(long index) {
            Object element = array[(int) index];
            if (element instanceof Map) {
                return new MapProxyObject((Map<String, Object>) element);
            } else if (element instanceof List) {
                return new ListProxyArray((List<Object>) element);
            } else if (element != null && element.getClass().isArray()) {
                return new ArrayProxyObject((Object[]) element);
            } else {
                return element;
            }
        }

        @Override
        public void set(long index, Value value) {
            array[(int) index] = value.isHostObject() ? value.asHostObject() : value;
        }
    }


    static Value runScript(Value function, Object... args) {
        var convertedArgs = Arrays.stream(args)
            .map(o -> {
                if (o instanceof Map<?,?>) {
                    var map = (Map<String, Object>) o;
                    return new MapProxyObject(map);
                }
                return o;
            }).toArray();
        var rval = function.execute(convertedArgs);
        log.atTrace().setMessage("rval={}").addArgument(rval).log();
        return rval;
    }

    static <T> CompletableFuture<T> runScriptAsFuture(Value function, Object... args) {
        return fromPromise(runScript(function, args));
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
