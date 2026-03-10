package org.opensearch.migrations.bulkload.pipeline.ir;

import java.util.Map;
import java.util.Objects;

/**
 * Source-agnostic document — the clean IR boundary between reading (any source)
 * and writing (any target).
 *
 * <p>Unlike source-specific types, this carries no source-coupled fields. ES-specific
 * concepts like {@code _type} and {@code routing} are carried in the opaque {@code hints}
 * map, which the pipeline core never reads — only source adapters populate it and
 * sink adapters consume it.
 *
 * <p>This is a value type: two {@code Document} instances with the same fields are equal.
 *
 * @param id             the document identifier, must not be null
 * @param source         the document body bytes, nullable for DELETE operations
 * @param operation      the operation type (UPSERT or DELETE), must not be null
 * @param hints          sink-specific routing hints (opaque to pipeline), never null
 * @param sourceMetadata source-specific diagnostic info (opaque to pipeline), never null
 */
public record Document(
    String id,
    byte[] source,
    Operation operation,
    Map<String, String> hints,
    Map<String, Object> sourceMetadata
) {
    /** Well-known hint keys for ES-compatible sinks. */
    public static final String HINT_TYPE = "_type";
    public static final String HINT_ROUTING = "routing";

    /** The type of document operation. */
    public enum Operation {
        /** Create or replace a document. */
        UPSERT,
        /** Delete a document. */
        DELETE
    }

    public Document {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(operation, "operation must not be null");
        hints = hints != null ? Map.copyOf(hints) : Map.of();
        sourceMetadata = sourceMetadata != null ? Map.copyOf(sourceMetadata) : Map.of();
    }

    /** Returns the length of the source bytes, or 0 if source is null (e.g. DELETE operations). */
    public int sourceLength() {
        return source != null ? source.length : 0;
    }
}
