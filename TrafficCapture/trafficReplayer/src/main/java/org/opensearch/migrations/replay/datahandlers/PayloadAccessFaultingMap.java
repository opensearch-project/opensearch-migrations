package org.opensearch.migrations.replay.datahandlers;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import org.opensearch.migrations.replay.datahandlers.http.StrictCaseInsensitiveHttpHeadersMap;
import org.opensearch.migrations.transform.JsonKeysForHttpMessage;

import io.netty.buffer.ByteBuf;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
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
    private Object jsonValue;
    private ByteBuf binaryValue;
    @Getter
    @Setter
    private boolean disableThrowingPayloadNotLoaded;

    public PayloadAccessFaultingMap(StrictCaseInsensitiveHttpHeadersMap headers) {
        isJson = Optional.ofNullable(headers.get("content-type"))
            .map(list -> list.stream().anyMatch(s -> s.startsWith("application/json")))
            .orElse(false);
    }

    @Override
    public boolean containsKey(Object key) {
        return (JsonKeysForHttpMessage.INLINED_JSON_BODY_DOCUMENT_KEY.equals(key) && jsonValue != null) ||
            (JsonKeysForHttpMessage.INLINED_BINARY_BODY_DOCUMENT_KEY.equals(key) && binaryValue != null);
    }

    private Object nullOrThrow() {
        if (disableThrowingPayloadNotLoaded) {
            return null;
        }
        throw PayloadNotLoadedException.getInstance();
    }

    @Override
    public Object get(Object key) {
        if (JsonKeysForHttpMessage.INLINED_JSON_BODY_DOCUMENT_KEY.equals(key)) {
            if (jsonValue == null) {
                return nullOrThrow();
            } else {
                return jsonValue;
            }
        } else if (JsonKeysForHttpMessage.INLINED_BINARY_BODY_DOCUMENT_KEY.equals(key)) {
            if (binaryValue == null) {
                return nullOrThrow();
            } else {
                return binaryValue;
            }
        } else {
            return null;
        }
    }

    @Override
    @NonNull
    public Set<Entry<String, Object>> entrySet() {
        if (jsonValue != null) {
            return Set.of(new SimpleEntry<>(JsonKeysForHttpMessage.INLINED_JSON_BODY_DOCUMENT_KEY, jsonValue));
        } else if (binaryValue != null) {
            return Set.of(new SimpleEntry<>(JsonKeysForHttpMessage.INLINED_BINARY_BODY_DOCUMENT_KEY, binaryValue));
        } else {
            return new AbstractSet<>() {
                @Override
                @NonNull
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
                                if (jsonValue != null) {
                                    return new SimpleEntry<>(
                                        JsonKeysForHttpMessage.INLINED_JSON_BODY_DOCUMENT_KEY,
                                        jsonValue
                                    );
                                } else if (binaryValue != null) {
                                    return new SimpleEntry<>(
                                        JsonKeysForHttpMessage.INLINED_BINARY_BODY_DOCUMENT_KEY,
                                        binaryValue
                                    );
                                } else {
                                    if (!disableThrowingPayloadNotLoaded) {
                                        throw PayloadNotLoadedException.getInstance();
                                    }
                                }
                            }
                            throw new NoSuchElementException();
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
        if (JsonKeysForHttpMessage.INLINED_JSON_BODY_DOCUMENT_KEY.equals(key)) {
            Object old = jsonValue;
            jsonValue = value;
            return old;
        } else if (JsonKeysForHttpMessage.INLINED_BINARY_BODY_DOCUMENT_KEY.equals(key)) {
            Object old = binaryValue;
            binaryValue = (ByteBuf) value;
            return old;
        } else {
            return null;
        }
    }

    @Override
    public String toString() {
        final var sb = new StringBuilder("PayloadFaultMap{");
        sb.append("isJson=").append(isJson);
        sb.append(", jsonValue=").append(jsonValue);
        sb.append(", binaryValue=").append(binaryValue);
        sb.append('}');
        return sb.toString();
    }
}
