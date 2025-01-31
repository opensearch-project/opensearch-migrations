package org.opensearch.migrations.transform.jsProxyObjects;

import java.util.List;
import java.util.Map;

import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyArray;

/**
 * Proxies a List so Graal sees it as a Java array-like object.
 */
public class ListProxyArray implements ProxyArray {

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
