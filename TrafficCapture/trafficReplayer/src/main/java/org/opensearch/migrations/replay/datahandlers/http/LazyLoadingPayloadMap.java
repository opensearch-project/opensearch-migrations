package org.opensearch.migrations.replay.datahandlers.http;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableSet;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
public class LazyLoadingPayloadMap extends AbstractMap<String, Object> {

    public static final String APPLICATION_JSON = "application/json";
    private static final TypeReference<LinkedHashMap<String, Object>> TYPE_REFERENCE_FOR_MAP_TYPE =
            new TypeReference<LinkedHashMap<String, Object>>(){};
    public static final String CONTENT_ENCODING = "content-encoding";
    public static final String CONTENT_TYPE = "content-type";
    public static final String UNZIPPED_JSON_BODY_DOCUMENT_KEY = "unzippedJsonBody";
    public static final String INLINED_JSON_BODY_DOCUMENT_KEY = "inlinedJsonBody";
    private final ObjectMapper objectMapper;

    public final static String INLINED_JSON_FIELD_NAME = "inlinedJsonBody";
    public final static String UNZIPPED_JSON_FIELD_NAME = "unzippedJsonBody";

    private final LoadingCache<String, Object> cache;
    private final static ImmutableSet<String> DIRECT_JSON_SET =
            ImmutableSet.<String>builder().add("").build();
    private final static ImmutableSet<String> UNZIPPED_JSON_SET =
            ImmutableSet.<String>builder().add("").build();
    private final static Set<String> TRANSIENT_UNION_SET =
            Stream.of(DIRECT_JSON_SET, UNZIPPED_JSON_SET)
                    .flatMap(Set::stream)
                    .collect(Collectors.toUnmodifiableSet());

    public LazyLoadingPayloadMap(Map<String, List<String>> headers,
                                 Supplier<InputStream> payloadSupplier) {
        final var encodings = getEncodings(headers.get(CONTENT_ENCODING));
        final var isJson =
                Optional.ofNullable(headers.get(CONTENT_TYPE)).map(s->s.equals(APPLICATION_JSON)).orElse(false);
        try {
            var allBytes = payloadSupplier.get().readAllBytes();
            log.error("payloadBytes=\n"+new String(allBytes, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        this.objectMapper = new ObjectMapper();
        this.cache = CacheBuilder.newBuilder()
                .build(new CacheLoader<String, Object>() {
                    @Override
                    public Object load(String key) {
                        if (!isJson) {
                            return null;
                        }
                        switch (key) {
                            case INLINED_JSON_BODY_DOCUMENT_KEY:
                                try {
                                    if (encodings != null) {
                                        return null;
                                    }
                                    return LazyLoadingPayloadMap.this.objectMapper.readValue(payloadSupplier.get(),
                                            LazyLoadingPayloadMap.TYPE_REFERENCE_FOR_MAP_TYPE);
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                            case UNZIPPED_JSON_BODY_DOCUMENT_KEY:
                            {
                                if (encodings == null) {
                                    return null;
                                }
                                throw new RuntimeException("Implement support to expand and parse gzipped streams!");
                            }
                        }
                        return null;
                    }
                });
        ;
    }

    private enum EncodingType {
        NONE, GZIP, DEFLATE, BR, UNKNOWN
    }

    private List<EncodingType> getEncodings(List<String> encodingStrings) {
        List<EncodingType> foundValues = Optional.of(encodingStrings).map(es ->
                        es.stream().map(s -> s.split(","))
                                .flatMap(splits -> Arrays.stream(splits))
                                .map(s -> {
                                    try {
                                        return EncodingType.valueOf(s);
                                    } catch (IllegalArgumentException e) {
                                        return EncodingType.NONE;
                                    }
                                })
                                .filter(et->EncodingType.NONE!=et)
                                .collect(Collectors.toList()))
                .orElse(null);
        return foundValues != null && foundValues.size() > 0 ? foundValues : null;
    }

    public boolean payloadWasLoaded() {
        return cache.getIfPresent(INLINED_JSON_BODY_DOCUMENT_KEY) != null ||
                cache.getIfPresent(UNZIPPED_JSON_BODY_DOCUMENT_KEY) != null;
    }

    public InputStream streamPayloadAsPerEncoding() {
        objectMapper.wr
    }

    @Override
    public Object get(Object key) {
        return cache.getUnchecked((String) key);
    }

    @Override
    public Set<Entry<String, Object>> entrySet() {
        return cache.asMap().entrySet();
    }

    @Override
    public Object put(String key, Object value) {
        var loadedCache = cache.getAllPresent(List.of(key));
        Object old = loadedCache.containsKey(key) ? loadedCache.get(key) : null;
        cache.put(key, value);
        return old;
    }
}
