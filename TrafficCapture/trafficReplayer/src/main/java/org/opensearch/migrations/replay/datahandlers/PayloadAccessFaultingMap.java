package org.opensearch.migrations.replay.datahandlers;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import org.opensearch.migrations.replay.datahandlers.http.StrictCaseInsensitiveHttpHeadersMap;
import org.opensearch.migrations.transform.JsonKeysForHttpMessage;

import lombok.EqualsAndHashCode;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

/**
 * This is a trivial map implementation that can only store one key-value pair, "inlinedJsonBody"
 * and more importantly, to raise a PayloadNotLoadedException when the mapping isn't present.
 * It is meant to be used in a highly specific use case where we optimistically try to NOT parse
 * the paylaod (unzip, parse, etc).  If a transform DOES require the payload to be present, get()
 *
 */
@EqualsAndHashCode(callSuper = false)
@Slf4j
public class PayloadAccessFaultingMap extends AbstractMap<String, Object> {

    private final boolean isJson;
    private Object onlyValue;

    public PayloadAccessFaultingMap(StrictCaseInsensitiveHttpHeadersMap headers) {
        isJson = Optional.ofNullable(headers.get("content-type"))
            .map(list -> list.stream().anyMatch(s -> s.startsWith("application/json")))
            .orElse(false);
    }

    @Override
    public Object get(Object key) {
        if (!JsonKeysForHttpMessage.INLINED_JSON_BODY_DOCUMENT_KEY.equals(key) || !isJson) {
            return null;
        }
        if (onlyValue == null) {
            throw PayloadNotLoadedException.getInstance();
        } else {
            return onlyValue;
        }
    }

    @Override
    public Object put(String key, Object value) {
        if (!JsonKeysForHttpMessage.INLINED_JSON_BODY_DOCUMENT_KEY.equals(key)) {
            return null;
        }
        Object old = onlyValue;
        onlyValue = value;
        return old;
    }

    @Override
    public @NonNull Set<Entry<String, Object>> entrySet() {
        if (onlyValue != null) {
            return Set.of(new SimpleEntry<>(JsonKeysForHttpMessage.INLINED_JSON_BODY_DOCUMENT_KEY, onlyValue));
        } else {
            return new AbstractSet<Entry<String, Object>>() {
                @Override
                public @NonNull Iterator<Entry<String, Object>> iterator() {
                    return makeInternalIterator();
                }

                @Override
                public int size() {
                    return isJson ? 1 : 0;
                }
            };
        }
    }

    private Iterator<Entry<String, Object>> makeInternalIterator() {
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
                        return new SimpleEntry<>(
                            JsonKeysForHttpMessage.INLINED_JSON_BODY_DOCUMENT_KEY,
                            onlyValue
                        );
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
    public String toString() {
        final StringBuilder sb = new StringBuilder("PayloadFaultMap{");
        sb.append("isJson=").append(isJson);
        sb.append(", onlyValue=").append(onlyValue);
        sb.append('}');
        return sb.toString();
    }
}
