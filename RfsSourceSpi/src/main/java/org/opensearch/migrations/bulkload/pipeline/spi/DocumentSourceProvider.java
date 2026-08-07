package org.opensearch.migrations.bulkload.pipeline.spi;

import java.io.IOException;

import org.opensearch.migrations.bulkload.pipeline.source.DocumentSource;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Builds a {@link DocumentSource} of one kind. Implementations are discovered with
 * {@link java.util.ServiceLoader} and must be registered in {@code META-INF/services}.
 *
 * <p>Parsing sits on the provider rather than on a Jackson {@code @JsonSubTypes} hierarchy: that
 * would need every subtype registered at compile time, which is exactly what discovery is for.
 *
 * @param <S> this provider's spec type
 */
public interface DocumentSourceProvider<S extends DocumentSourceSpec> {

    /** Selects this provider. Compared after normalization (trimmed, lower-cased). */
    String kind();

    /** Parse this provider's own config. The kind selects the provider; the provider reads the rest. */
    S parseSpec(JsonNode config);

    /** Reject a malformed source before construction. May do I/O. */
    default void validate(S spec, SourceRuntime runtime) {
        // Nothing to check by default.
    }

    /**
     * True if construction is expensive enough that the caller should check for remaining work
     * first. Lets a restarted worker with nothing left to do skip the setup entirely.
     */
    default boolean deferUntilWorkAvailable() {
        return false;
    }

    DocumentSource create(S spec, SourceRuntime runtime) throws IOException;

    /**
     * Parse, validate, create. Keeps {@code S} from leaking to callers holding a wildcard-typed
     * provider.
     */
    default DocumentSource open(JsonNode config, SourceRuntime runtime) throws IOException {
        S spec = parseSpec(config);
        validate(spec, runtime);
        return create(spec, runtime);
    }
}
