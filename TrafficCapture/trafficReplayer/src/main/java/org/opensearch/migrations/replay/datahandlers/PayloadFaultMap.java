package org.opensearch.migrations.replay.datahandlers;

import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.datahandlers.http.StrictCaseInsensitiveHttpHeadersMap;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

/**
 * This is a trivial map implementation that can only store one key-value pair, "inlinedJsonBody"
 * and more importantly, to raise a PayloadNotLoadedException when the mapping isn't present.
 * It is meant to be used in a highly specific use case where we optimistically try to NOT parse
 * the paylaod (unzip, parse, etc).  If a transform DOES require the payload to be present, get()
 *
 */
@Slf4j
public class PayloadFaultMap extends AbstractMap<String, Object> {

    public static final String CONTENT_TYPE = "content-type";
    public static final String APPLICATION_JSON = "application/json";
    public static final String INLINED_JSON_BODY_DOCUMENT_KEY = "inlinedJsonBody";
    private static final TypeReference<LinkedHashMap<String, Object>> TYPE_REFERENCE_FOR_MAP_TYPE =
            new TypeReference<LinkedHashMap<String, Object>>(){};

    private final boolean isJson;
    private Object onlyValue;

    public PayloadFaultMap(StrictCaseInsensitiveHttpHeadersMap headers) {
        isJson = Optional.ofNullable(headers.get(CONTENT_TYPE))
                .map(list->list.stream()
                        .anyMatch(s->s.startsWith(APPLICATION_JSON))).orElse(false);
    }

    @Override
    public Object get(Object key) {
        if (!INLINED_JSON_BODY_DOCUMENT_KEY.equals(key) || !isJson) { return null; }
        if (onlyValue == null) {
            throw PayloadNotLoadedException.getInstance();
        } else {
            return onlyValue;
        }
    }

    @Override
    public Set<Entry<String, Object>> entrySet() {
        if (onlyValue != null) {
            return Set.of(new SimpleEntry<>(INLINED_JSON_BODY_DOCUMENT_KEY, onlyValue));
        } else {
            return new AbstractSet<Entry<String, Object>>() {
                @Override
                public Iterator<Entry<String, Object>> iterator() {
                    return new Iterator<>() {
                        private int count;
                        @Override
                        public boolean hasNext() {
                            return count == 0 && isJson;
                        }

                        @Override
                        public Entry<String, Object> next() {
                            if (isJson && count == 0) {
                                ++count;
                                if (onlyValue != null) {
                                    return new SimpleEntry(INLINED_JSON_BODY_DOCUMENT_KEY, onlyValue);
                                } else {
                                    throw PayloadNotLoadedException.getInstance();
                                }
                            } else {
                                throw new NoSuchElementException();
                            }
                        }
                    };
                }

                @Override
                public int size() {
                    return isJson ? 1 : 0;
                }
            };
        }
    }

    @Override
    public Object put(String key, Object value) {
        if (!INLINED_JSON_BODY_DOCUMENT_KEY.equals(key)) { return null; }
        Object old = onlyValue;
        onlyValue = value;
        return old;
    }
}
