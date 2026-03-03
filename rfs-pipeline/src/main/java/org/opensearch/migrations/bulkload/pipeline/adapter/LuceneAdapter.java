package org.opensearch.migrations.bulkload.pipeline.adapter;

import org.opensearch.migrations.bulkload.common.DocumentChangeType;
import org.opensearch.migrations.bulkload.common.LuceneDocumentChange;
import org.opensearch.migrations.bulkload.pipeline.ir.DocumentChange;

/**
 * Converts between existing Lucene-specific types and the clean pipeline IR.
 *
 * <p>This adapter is the bridge between the existing codebase and the clean pipeline.
 * It lives in the adapter package — the pipeline core never imports Lucene types directly.
 *
 * <p>The conversion carries {@code luceneDocNumber} through for progress tracking —
 * the pipeline uses the minimum doc number across in-flight batches as the safe resume point.
 */
public final class LuceneAdapter {

    private LuceneAdapter() {}

    /**
     * Convert a {@link LuceneDocumentChange} to a clean {@link DocumentChange}.
     *
     * @param luceneDoc the Lucene-specific document change
     * @return a clean IR document change with luceneDocNumber preserved
     */
    public static DocumentChange fromLucene(LuceneDocumentChange luceneDoc) {
        return new DocumentChange(
            luceneDoc.getId(),
            luceneDoc.getType(),
            luceneDoc.getSource(),
            luceneDoc.getRouting(),
            mapChangeType(luceneDoc.getOperation()),
            luceneDoc.getLuceneDocNumber()
        );
    }

    private static DocumentChange.ChangeType mapChangeType(DocumentChangeType luceneType) {
        return switch (luceneType) {
            case INDEX -> DocumentChange.ChangeType.INDEX;
            case DELETE -> DocumentChange.ChangeType.DELETE;
        };
    }
}
