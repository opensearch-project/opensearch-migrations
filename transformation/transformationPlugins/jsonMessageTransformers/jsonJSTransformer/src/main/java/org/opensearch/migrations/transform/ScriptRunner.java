package org.opensearch.migrations.transform;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyArray;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;

@Slf4j
public class ScriptRunner {
    protected final Context context;

    public ScriptRunner() {
        this.context = Context.newBuilder("js")
            .allowAllAccess(true)
            .build();
    }

    void setGlobal(String name, Object value) {
        var bindings = context.getBindings("js");
        if (value instanceof Map) {
            bindings.putMember(name, new MapProxyObject((Map) value));
        } else {
            bindings.putMember(name, value);
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


    Value runScript(String script) {
        var rval = context.eval("js", script);
        log.atTrace().setMessage("rval={}").addArgument(rval).log();
        return rval;
    }

    <T> CompletableFuture<T> runScriptAsFuture(String script) {
        return ScriptRunner.fromPromise(runScript(script));
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
