package org.opensearch.migrations.bulkload.pipeline.adapter;

import java.util.HashMap;
import java.util.Map;

import org.opensearch.migrations.bulkload.common.DocumentChangeType;
import org.opensearch.migrations.bulkload.common.LuceneDocumentChange;
import org.opensearch.migrations.bulkload.pipeline.model.Document;

/**
 * Converts between existing Lucene-specific types and the clean pipeline IR.
 *
 * <p>This adapter is the bridge between the existing codebase and the clean pipeline.
 * It lives in the adapter package — the pipeline core never imports Lucene types directly.
 *
 * <p>Populates {@link Document#hints()} with ES-specific fields ({@code _type}, {@code routing},
 * and {@code version})
 * and {@link Document#sourceMetadata()} with {@code luceneDocNumber} and the snapshot's {@code _version}.
 */
public final class LuceneAdapter {

    private final boolean emitDocType;

    public LuceneAdapter() {
        this(false);
    }

    /**
     * @param emitDocType when true, propagates the ES {@code _type} field into
     *                    {@link Document#hints()} for downstream transformers that need it
     *                    (e.g. TypeMappingSanitizationTransformer for ES 5.x multi-type indices)
     */
    public LuceneAdapter(boolean emitDocType) {
        this.emitDocType = emitDocType;
    }

    public Document fromLucene(LuceneDocumentChange luceneDoc) {
        var hints = new HashMap<String, String>();
        if (emitDocType && luceneDoc.getType() != null) {
            hints.put(Document.HINT_TYPE, luceneDoc.getType());
        }
        if (luceneDoc.getRouting() != null) {
            hints.put(Document.HINT_ROUTING, luceneDoc.getRouting());
        }
        String version = luceneDoc.getVersion() == null ? null : luceneDoc.getVersion().toString();
        if (version != null && luceneDoc.getOperation() == DocumentChangeType.INDEX
            && luceneDoc.getId() != null && !luceneDoc.getId().isEmpty()) {
            hints.put(Document.HINT_VERSION, version);
        }
        Map<String, Object> sourceMetadata = version == null
            ? Map.of(Document.SOURCE_META_LUCENE_DOC_NUMBER, luceneDoc.getLuceneDocNumber())
            : Map.of(Document.SOURCE_META_LUCENE_DOC_NUMBER, luceneDoc.getLuceneDocNumber(),
                Document.SOURCE_META_VERSION, version);

        return new Document(
            luceneDoc.getId(),
            luceneDoc.getSource(),
            mapOperation(luceneDoc.getOperation()),
            hints,
            sourceMetadata
        );
    }

    private static Document.Operation mapOperation(DocumentChangeType luceneType) {
        return switch (luceneType) {
            case INDEX -> Document.Operation.UPSERT;
            case DELETE -> Document.Operation.DELETE;
        };
    }
}
