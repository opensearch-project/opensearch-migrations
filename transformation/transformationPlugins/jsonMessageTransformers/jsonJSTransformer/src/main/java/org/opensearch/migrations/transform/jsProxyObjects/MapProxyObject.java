package org.opensearch.migrations.transform.jsProxyObjects;

import java.util.List;
import java.util.Map;

import lombok.Getter;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyObject;

@Getter
public class MapProxyObject implements ProxyObject {

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
