package org.opensearch.migrations.replay.datahandlers;

import java.util.Optional;
import java.util.TreeMap;

import org.opensearch.migrations.replay.datahandlers.http.StrictCaseInsensitiveHttpHeadersMap;

import lombok.EqualsAndHashCode;
import lombok.Getter;
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
public class PayloadAccessFaultingMap extends TreeMap<String, Object> {

    private final boolean isJson;
    @Getter
    @Setter
    private boolean disableThrowingPayloadNotLoaded;

    public PayloadAccessFaultingMap(StrictCaseInsensitiveHttpHeadersMap headers) {
        isJson = Optional.ofNullable(headers.get("content-type"))
            .map(list -> list.stream().anyMatch(s -> s.startsWith("application/json")))
            .orElse(false);
    }

    private Object nullOrThrow() {
        if (disableThrowingPayloadNotLoaded) {
            return null;
        }
        throw PayloadNotLoadedException.getInstance();
    }

    @Override
    public Object get(Object key) {
        var value = super.get(key);
        if (value == null && !disableThrowingPayloadNotLoaded) {
            throw PayloadNotLoadedException.getInstance();
        }
        return value;
    }
}
