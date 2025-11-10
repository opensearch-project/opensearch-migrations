package org.opensearch.migrations.replay.datahandlers;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import org.opensearch.migrations.replay.datahandlers.http.StrictCaseInsensitiveHttpHeadersMap;

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
@SuppressWarnings({"java:S1068", "java:S2160"})
@EqualsAndHashCode(callSuper = false)
@Slf4j
public class PayloadAccessFaultingMap extends AbstractMap<String, Object> {

    private final boolean isJson;
    TreeMap<String, Object> underlyingMap;
    @Getter
    @Setter
    private boolean disableThrowingPayloadNotLoaded;
    private boolean payloadWasAccessed;

    public PayloadAccessFaultingMap(StrictCaseInsensitiveHttpHeadersMap headers) {
        disableThrowingPayloadNotLoaded = true;
        underlyingMap = new TreeMap<>();
        isJson = Optional.ofNullable(headers.get("content-type"))
            .map(list -> list.stream().anyMatch(s -> s.startsWith("application/json")))
            .orElse(false);
    }

    @Override
    @NonNull
    public Set<Map.Entry<String, Object>> entrySet() {
        if (underlyingMap.isEmpty() && !disableThrowingPayloadNotLoaded) {
            return new AbstractSet<>() {
                @Override
                @NonNull
                public Iterator<Map.Entry<String, Object>> iterator() {
                    return new Iterator<>() {
                        @Override
                        public boolean hasNext() {
                            throw makeFault();
                        }

                        @Override
                        public Map.Entry<String, Object> next() {
                            throw makeFault();
                        }
                    };
                }

                @Override
                public int size() {
                    throw makeFault();
                }
            };
        } else {
            return underlyingMap.entrySet();
        }
    }

    @Override
    public Object put(String key, Object value) {
        return underlyingMap.put(key, value);
    }

    @Override
    public Object get(Object key) {
        var value = super.get(key);
        if (value == null && !disableThrowingPayloadNotLoaded) {
            throw makeFault();
        }
        return value;
    }

    public boolean missingPayloadWasAccessed() {
        return payloadWasAccessed;
    }

    public void resetMissingPayloadWasAccessed() {
        payloadWasAccessed = false;
    }

    private PayloadNotLoadedException makeFault() throws PayloadNotLoadedException {
        payloadWasAccessed = true;
        return PayloadNotLoadedException.getInstance();
    }
}
