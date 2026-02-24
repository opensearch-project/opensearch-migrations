/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package org.opensearch.migrations.transform.shim;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.transform.JsonCompositeTransformer;

/**
 * A registry of named bidirectional transformations that can be composed.
 * <p>
 * Each named entry is a request/response transformer pair. Compose all or a subset
 * by name — they chain in registration order via {@link JsonCompositeTransformer}.
 *
 * <pre>{@code
 * var library = new TransformationLibrary()
 *     .register("uri-rewrite", uriReqTransform, identityTransform)
 *     .register("response-format", identityTransform, respFormatTransform);
 *
 * // Compose all registered transforms
 * TransformationPair all = library.composeAll();
 *
 * // Or select specific ones
 * TransformationPair selected = library.compose("uri-rewrite");
 * }</pre>
 */
public class TransformationLibrary {

    /** A paired request + response transformer. */
    @SuppressWarnings({"java:S100", "java:S1186"}) // Record accessors are auto-generated
    public record TransformationPair(IJsonTransformer request, IJsonTransformer response) {}

    private final Map<String, TransformationPair> entries = new LinkedHashMap<>();

    /** Register a named bidirectional transformation. Returns this for chaining. */
    public TransformationLibrary register(String name, IJsonTransformer request, IJsonTransformer response) {
        entries.put(name, new TransformationPair(request, response));
        return this;
    }

    /** Compose all registered transformations in registration order. */
    public TransformationPair composeAll() {
        return compose(entries.keySet().toArray(String[]::new));
    }

    /** Compose selected transformations by name, in the order specified. */
    public TransformationPair compose(String... names) {
        var reqTransformers = Arrays.stream(names)
            .map(this::getEntry)
            .map(TransformationPair::request)
            .toArray(IJsonTransformer[]::new);
        var respTransformers = Arrays.stream(names)
            .map(this::getEntry)
            .map(TransformationPair::response)
            .toArray(IJsonTransformer[]::new);
        return new TransformationPair(
            new JsonCompositeTransformer(reqTransformers),
            new JsonCompositeTransformer(respTransformers)
        );
    }

    /** Get registered names in registration order. */
    public String[] getNames() {
        return entries.keySet().toArray(String[]::new);
    }

    private TransformationPair getEntry(String name) {
        var entry = entries.get(name);
        if (entry == null) {
            throw new IllegalArgumentException("No transformation registered with name: " + name);
        }
        return entry;
    }
}
