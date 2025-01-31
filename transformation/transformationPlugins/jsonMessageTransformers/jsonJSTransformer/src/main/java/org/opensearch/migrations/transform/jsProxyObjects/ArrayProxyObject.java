package org.opensearch.migrations.transform.jsProxyObjects;

import java.util.List;
import java.util.Map;

import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyArray;

/**
 * Proxies an actual Java array (T[]) so Graal sees it as an array-like object.
 */
public class ArrayProxyObject implements ProxyArray {

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
