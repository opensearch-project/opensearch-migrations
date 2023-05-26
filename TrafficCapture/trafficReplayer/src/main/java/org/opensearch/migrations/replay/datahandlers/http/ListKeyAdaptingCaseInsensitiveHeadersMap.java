package org.opensearch.migrations.replay.datahandlers.http;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The underlying case-insensitive headers map allows for headers to be stored as a multimap,
 * with a list of values.  However, this class spares callers (and third party packages) the
 * difficulty of working with a multimap when they might just have a single value.
 *
 * This is a kludge to provide that.  Note that this code doesn't do conversions such as joining
 * or splitting.  If more control is required, callers should use the multimap interfaces.
 */
public class ListKeyAdaptingCaseInsensitiveHeadersMap extends AbstractMap<String,Object> {
    protected final StrictCaseInsensitiveHttpHeadersMap strictHeadersMap;

    public ListKeyAdaptingCaseInsensitiveHeadersMap(StrictCaseInsensitiveHttpHeadersMap mapToWrap) {
        strictHeadersMap = mapToWrap;
    }

    public StrictCaseInsensitiveHttpHeadersMap asStrictMap() {
        return strictHeadersMap;
    }

    @Override
    public Object get(Object key) {
        return strictHeadersMap.get(key);
    }

    @Override
    public List<String> put(String key, Object value) {
        List<String> strList;
        if (value instanceof List) {
            if (((List) value).stream().allMatch(item -> item instanceof String)) {
                strList = (List) value;
            } else {
                strList = (List) ((List) value).stream()
                        .map(item -> item.toString())
                        .collect(Collectors.toCollection(ArrayList::new));
            }
        } else {
            strList = new ArrayList<>(1);
            strList.add(value.toString());
        }
        return strictHeadersMap.put(key, strList);
    }

    /**
     * This is effectively just casting the underlying object's entrySet, but I'm not sure how
     * to indicate that more concisely to Java
     */
    @Override
    public Set<Entry<String, Object>> entrySet() {
//        return new AbstractSet() {
//            @Override
//            public Iterator<Entry<String, Object>> iterator() {
//                return new Iterator<Entry<String, Object>>() {
//                    Iterator<Entry<String,List<String>>> backingIterator =
//                            strictHeadersMap.entrySet().iterator();
//
//                    @Override
//                    public boolean hasNext() {
//                        return backingIterator.hasNext();
//                    }
//
//                    @Override
//                    public Entry<String, Object> next() {
//                        var backingEntry = backingIterator.next();
//                        return backingEntry == null ? null : (Entry) backingEntry;
//                    }
//                };
//            }
//
//            @Override
//            public int size() {
//                return strictHeadersMap.size();
//            }
//        };
        return (Set<Entry<String, Object>>) (Object) strictHeadersMap.entrySet();
    }
}
