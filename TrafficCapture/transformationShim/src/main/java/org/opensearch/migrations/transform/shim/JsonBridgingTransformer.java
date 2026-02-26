/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package org.opensearch.migrations.transform.shim;

import java.util.Map;

import org.opensearch.migrations.transform.IJsonTransformer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * Wraps an {@link IJsonTransformer} with JSON serialization/deserialization to avoid
 * GraalVM polyglot interop issues. When JS modifies properties on a Java Map proxy,
 * the changes may not reflect back to the Java Map. This wrapper serializes the input
 * to JSON, deserializes to a fresh Map, passes it through the transform, then
 * serializes/deserializes the result to ensure a clean Java Map is returned.
 */
@Slf4j
public class JsonBridgingTransformer implements IJsonTransformer, AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final IJsonTransformer delegate;

    public JsonBridgingTransformer(IJsonTransformer delegate) {
        this.delegate = delegate;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object transformJson(Object incomingJson) {
        try {
            // Serialize input to JSON and back to break Java Map proxy
            String inputJson = MAPPER.writeValueAsString(incomingJson);
            Map<String, Object> cleanInput = MAPPER.readValue(inputJson, MAP_TYPE);

            Object result = delegate.transformJson(cleanInput);

            // If result is a String (JSON), parse it; otherwise serialize/deserialize
            if (result instanceof String) {
                return MAPPER.readValue((String) result, MAP_TYPE);
            } else if (result instanceof Map) {
                // Serialize and deserialize to capture any shadow properties
                String resultJson = MAPPER.writeValueAsString(result);
                return MAPPER.readValue(resultJson, MAP_TYPE);
            }
            return result;
        } catch (Exception e) {
            log.warn("JSON bridging failed, falling back to direct call", e);
            return delegate.transformJson(incomingJson);
        }
    }

    @Override
    public void close() throws Exception {
        if (delegate instanceof AutoCloseable) {
            ((AutoCloseable) delegate).close();
        }
    }
}
