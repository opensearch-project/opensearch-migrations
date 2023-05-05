package org.opensearch.migrations.replay.datahandlers.http;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

public class StrictCaseInsensitiveHttpHeadersMap extends AbstractMap<String,List<String>> {
    protected LinkedHashMap<String, SimpleEntry<String, List<String>>> lowerCaseToUpperCaseAndValueMap;

    public StrictCaseInsensitiveHttpHeadersMap() {
        lowerCaseToUpperCaseAndValueMap = new LinkedHashMap<>();
    }

    @Override
    public List<String> get(Object key) {
        String keyStr = !(key instanceof String) ? key.toString() : (String) key;
        var kvp = lowerCaseToUpperCaseAndValueMap.get(keyStr.toLowerCase());
        return kvp == null ? null : kvp.getValue();
    }

    @Override
    public List<String> put(String key, List<String> value) {
        SimpleEntry<String,List<String>> existing =
                lowerCaseToUpperCaseAndValueMap.put(key.toLowerCase(), new SimpleEntry(key, value));
        return existing == null ? null : existing.getValue();
    }

    @Override
    public Set<Entry<String, List<String>>> entrySet() {
        return new AbstractSet() {
            @Override
            public Iterator<Entry<String, List<String>>> iterator() {
                return new Iterator<Entry<String, List<String>>>() {
                    Iterator<Entry<String,SimpleEntry<String,List<String>>>> backingIterator =
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
}
