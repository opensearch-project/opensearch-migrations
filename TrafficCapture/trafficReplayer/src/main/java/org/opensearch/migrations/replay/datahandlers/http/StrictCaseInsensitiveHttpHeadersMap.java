package org.opensearch.migrations.replay.datahandlers.http;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import lombok.EqualsAndHashCode;

/**
 * This implements an ordered map of headers. The order is the same as the order that the keys were
 * added.  We want to play them back in the same order. . LinkedHashMap is used to keep the objects
 * so that we can maintain that original order.  However, we add an extra ability to keep key values
 * (or header names) case insensitive.
 */
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
        var kvp = lowerCaseToUpperCaseAndValueMap.get(objectToCanonicalFormatString(key));
        return kvp == null ? null : kvp.getValue();
    }

    @Override
    public List<String> put(String incomingKey, List<String> value) {
        var normalizedKey = objectToCanonicalFormatString(incomingKey);
        SimpleEntry<String, List<String>> oldEntry = lowerCaseToUpperCaseAndValueMap.get(normalizedKey);
        var newValue = new SimpleEntry<>(oldEntry == null ? incomingKey : oldEntry.getKey(), value);
        lowerCaseToUpperCaseAndValueMap.put(normalizedKey, newValue);
        return oldEntry == null ? null : oldEntry.getValue();
    }

    @Override
    public List<String> remove(Object key) {
        var origKeyAndVal = lowerCaseToUpperCaseAndValueMap.remove(objectToCanonicalFormatString(key));
        return origKeyAndVal == null ? null : origKeyAndVal.getValue();
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
                };
            }

            @Override
            public int size() {
                return lowerCaseToUpperCaseAndValueMap.size();
            }
        };
    }

    @Override
    public boolean containsKey(Object key) {
        return lowerCaseToUpperCaseAndValueMap.containsKey(objectToCanonicalFormatString(key));
    }

    private String objectToCanonicalFormatString(Object key) {
        if (key instanceof String) {
            return ((String) key).toLowerCase(Locale.ROOT);
        }
        throw new IllegalArgumentException("Invalid key type, expected String but was " + key.getClass().getName());
    }
}
