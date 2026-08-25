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

    /**
     * Where this source's work coordination can live. Sources that cannot write coordination state
     * to the target return {@link CoordinationRequirement#EXTERNAL_REQUIRED} so the caller can
     * insist on an external coordinator without testing for a concrete provider type.
     */
    default CoordinationRequirement coordinationRequirement() {
        return CoordinationRequirement.TARGET_ALLOWED;
    }

    /**
     * True when this spec makes the source download or derive data into
     * {@link SourceRuntime#scratchDir()}. Depends on the spec, not on the provider alone: the same
     * source may read a local repository in place and download a remote one.
     *
     * <p>The caller must supply a real directory when this is true; a temporary directory is not an
     * acceptable substitute for content that can be large and is expensive to re-fetch.
     */
    default boolean requiresScratchDirectory(S spec) {
        return false;
    }

    /**
     * True when this spec makes the source unpack data into {@link SourceRuntime#workDir()}.
     * Sources that read their input in place return false, and the caller may then leave
     * {@code workDir()} pointing at a location the source never touches.
     */
    default boolean requiresWorkingDirectory(S spec) {
        return true;
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
