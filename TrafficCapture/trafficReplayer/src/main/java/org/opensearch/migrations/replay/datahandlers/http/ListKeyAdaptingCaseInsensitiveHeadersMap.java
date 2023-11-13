package org.opensearch.migrations.replay.datahandlers.http;

import lombok.EqualsAndHashCode;

import java.util.AbstractMap;
import java.util.ArrayList;
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
@EqualsAndHashCode(callSuper = false)
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
            if (((List) value).stream().allMatch(String.class::isInstance)) {
                strList = (List) value;
            } else {
                strList = (List) ((List) value).stream()
                        .map(Object::toString)
                        .collect(Collectors.toCollection(ArrayList::new));
            }
        } else {
            strList = new ArrayList<>(1);
            strList.add(value.toString());
        }
        return strictHeadersMap.put(key, strList);
    }

    @Override
    public List<String> remove(Object key) {
        return strictHeadersMap.remove(key);
    }


    /**
     * This is just casting the underlying object's entrySet.  An old git commit will show this unrolled,
     * but this should be significantly more efficient.
     */
    @Override
    public Set<Entry<String, Object>> entrySet() {
        return (Set<Entry<String, Object>>) (Object) strictHeadersMap.entrySet();
    }
}
