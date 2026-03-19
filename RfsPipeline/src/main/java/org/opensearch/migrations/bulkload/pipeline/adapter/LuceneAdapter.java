package org.opensearch.migrations.bulkload.pipeline.adapter;

import java.util.HashMap;
import java.util.Map;

import org.opensearch.migrations.bulkload.common.DocumentChangeType;
import org.opensearch.migrations.bulkload.common.LuceneDocumentChange;
import org.opensearch.migrations.bulkload.pipeline.ir.Document;

/**
 * Converts between existing Lucene-specific types and the clean pipeline IR.
 *
 * <p>This adapter is the bridge between the existing codebase and the clean pipeline.
 * It lives in the adapter package — the pipeline core never imports Lucene types directly.
 *
 * <p>Populates {@link Document#hints()} with ES-specific fields ({@code _type}, {@code routing})
 * and {@link Document#sourceMetadata()} with diagnostic info ({@code luceneDocNumber}).
 */
public final class LuceneAdapter {

    private LuceneAdapter() {}

    /**
     * Convert a {@link LuceneDocumentChange} to a clean {@link Document}.
     *
     * @param luceneDoc the Lucene-specific document change
     * @return a clean IR document with hints and sourceMetadata populated
     */
    public static Document fromLucene(LuceneDocumentChange luceneDoc) {
        var hints = new HashMap<String, String>();
        if (luceneDoc.getType() != null) {
            hints.put(Document.HINT_TYPE, luceneDoc.getType());
        }
        if (luceneDoc.getRouting() != null) {
            hints.put(Document.HINT_ROUTING, luceneDoc.getRouting());
        }

        return new Document(
            luceneDoc.getId(),
            luceneDoc.getSource(),
            mapOperation(luceneDoc.getOperation()),
            hints,
            Map.of(Document.SOURCE_META_LUCENE_DOC_NUMBER, luceneDoc.getLuceneDocNumber())
        );
    }

    private static Document.Operation mapOperation(DocumentChangeType luceneType) {
        return switch (luceneType) {
            case INDEX -> Document.Operation.UPSERT;
            case DELETE -> Document.Operation.DELETE;
        };
    }
}
