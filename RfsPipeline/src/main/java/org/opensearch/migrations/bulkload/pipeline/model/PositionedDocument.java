package org.opensearch.migrations.bulkload.pipeline.model;

import java.util.Objects;

/**
 * A document paired with the cursor that resumes immediately after it.
 *
 * <p>The cursor is opaque to the pipeline — only the source that emitted it interprets it — and must
 * be valid UTF-8, because work-item ids base64url-encode it.
 *
 * @param document   the document, must not be null
 * @param cursorAfter a source-defined token that resumes reading after {@code document}, must not be null
 */
public record PositionedDocument(Document document, String cursorAfter) {

    public PositionedDocument {
        Objects.requireNonNull(document, "document must not be null");
        Objects.requireNonNull(cursorAfter, "cursorAfter must not be null");
    }
}
