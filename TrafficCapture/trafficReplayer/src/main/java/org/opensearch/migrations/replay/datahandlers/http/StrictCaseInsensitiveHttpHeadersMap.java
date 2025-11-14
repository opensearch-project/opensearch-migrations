package org.opensearch.migrations.replay.datahandlers.http;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.EqualsAndHashCode;

/**
 * This implements an ordered map of headers. The order is the same as the order that the keys were
 * added.  We want to play them back in the same order. . LinkedHashMap is used to keep the objects
 * so that we can maintain that original order.  However, we add an extra ability to keep key values
 * (or header names) case-insensitive.
 */
@SuppressWarnings("java:S2160")
@EqualsAndHashCode(callSuper = false)
public class StrictCaseInsensitiveHttpHeadersMap extends AbstractMap<String, List<String>> {
    protected LinkedHashMap<String, SimpleEntry<String, List<String>>> lowerCaseToUpperCaseAndValueMap;

    public StrictCaseInsensitiveHttpHeadersMap() {
        lowerCaseToUpperCaseAndValueMap = new LinkedHashMap<>();
    }

    public static StrictCaseInsensitiveHttpHeadersMap fromMap(Map<String, List<String>> map) {
        var caseInsensitiveHttpHeadersMap = new StrictCaseInsensitiveHttpHeadersMap();
        caseInsensitiveHttpHeadersMap.putAll(map);
        return caseInsensitiveHttpHeadersMap;
    }

    @Override
    public List<String> get(Object key) {
        String keyStr = !(key instanceof String) ? key.toString() : (String) key;
        var kvp = lowerCaseToUpperCaseAndValueMap.get(keyStr.toLowerCase());
        return kvp == null ? null : kvp.getValue();
    }

    @Override
    public List<String> put(String incomingKey, List<String> value) {
        var normalizedKey = incomingKey.toLowerCase();
        SimpleEntry<String, List<String>> oldEntry = lowerCaseToUpperCaseAndValueMap.get(normalizedKey);
        var newValue = new SimpleEntry<>(oldEntry == null ? incomingKey : oldEntry.getKey(), value);
        lowerCaseToUpperCaseAndValueMap.put(normalizedKey, newValue);
        return oldEntry == null ? null : oldEntry.getValue();
    }

    @Override
    public List<String> remove(Object key) {
        String keyStr = (key instanceof String) ? (String) key : key.toString();
        var origKeyAndVal = lowerCaseToUpperCaseAndValueMap.remove(keyStr.toLowerCase());
        return origKeyAndVal == null ? null : origKeyAndVal.getValue();
    }

    @Override
    public boolean containsKey(Object key) {
        String keyStr = (key instanceof String) ? (String) key : key.toString();
        return lowerCaseToUpperCaseAndValueMap.containsKey(keyStr.toLowerCase());
    }

    @Override
    public Set<Entry<String, List<String>>> entrySet() {
        return new AbstractSet<>() {
            @Override
            public Iterator<Entry<String, List<String>>> iterator() {
                return new Iterator<>() {
                    Iterator<Entry<String, SimpleEntry<String, List<String>>>> backingIterator =
                            lowerCaseToUpperCaseAndValueMap.entrySet().iterator();

                    @Override
                    public boolean hasNext() {
                        return backingIterator.hasNext();
                    }

                    @Override
                    public Entry<String, List<String>> next() {
                        var backingEntry = backingIterator.next();
                        return backingEntry == null ? null : backingEntry.getValue();
                    }

                    @Override
                    public void remove() {
                        backingIterator.remove();
                    }
                };
            }

            @Override
            public int size() {
                return lowerCaseToUpperCaseAndValueMap.size();
            }
        };
    }

    // Custom keySet that supports case-insensitive removals.
    @Override
    public Set<String> keySet() {
        return new AbstractSet<>() {
            @Override
            public Iterator<String> iterator() {
                return lowerCaseToUpperCaseAndValueMap.keySet().iterator();
            }

            @Override
            public int size() {
                return lowerCaseToUpperCaseAndValueMap.size();
            }

            @Override
            public boolean contains(Object key) {
                String keyStr = (key instanceof String) ? (String) key : key.toString();
                return lowerCaseToUpperCaseAndValueMap.containsKey(keyStr.toLowerCase());
            }

            @Override
            public boolean remove(Object key) {
                String keyStr = (key instanceof String) ? (String) key : key.toString();
                return lowerCaseToUpperCaseAndValueMap.remove(keyStr.toLowerCase()) != null;
            }
        };
    }
}
