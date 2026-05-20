package org.opensearch.migrations.bulkload.solr;

import java.io.IOException;

import org.opensearch.migrations.bulkload.common.DocumentChangeType;
import org.opensearch.migrations.bulkload.common.LuceneDocumentChange;
import org.opensearch.migrations.bulkload.lucene.FieldMappingContext;
import org.opensearch.migrations.bulkload.lucene.LuceneLeafReader;
import org.opensearch.migrations.bulkload.lucene.SourceReconstructor;

import lombok.extern.slf4j.Slf4j;

/**
 * Reads Solr documents from Lucene segments by delegating to {@link SourceReconstructor},
 * the same code path used for ES sourceless migrations. Solr has no {@code _source} field,
 * so every document is effectively "sourceless" and gets reconstructed from stored fields
 * (and optionally doc_values) via the shared reconstruction logic.
 */
@Slf4j
final class SolrLuceneDocReader {

    private SolrLuceneDocReader() {}

    static LuceneDocumentChange getDocument(
        LuceneLeafReader reader,
        int luceneDocId,
        boolean isLive,
        int segmentDocBase,
        DocumentChangeType operation,
        FieldMappingContext mappingContext
    ) {
        if (!isLive) {
            return null;
        }

        var document = readDocument(reader, luceneDocId);

        // Extract id separately: SourceReconstructor skips _-prefixed fields but Solr's "id" has no prefix
        String docId = extractSolrId(document);
        if (docId == null) {
            docId = "solr_doc_" + (segmentDocBase + luceneDocId);
        }

        // Solr has no _source field — use flat mode since Solr field names with dots are literal, not nested
        String sourceJson = SourceReconstructor.reconstructSourceFlat(reader, luceneDocId, document, mappingContext);
        if (sourceJson == null || sourceJson.isEmpty()) {
            log.atWarn()
                .setMessage("Solr document {} has no reconstructable fields, skipping")
                .addArgument(docId)
                .log();
            return null;
        }

        return new LuceneDocumentChange(
            segmentDocBase + luceneDocId, docId, null,
            sourceJson.getBytes(java.nio.charset.StandardCharsets.UTF_8), null, operation);
    }

    private static org.opensearch.migrations.bulkload.lucene.LuceneDocument readDocument(
        LuceneLeafReader reader, int luceneDocId
    ) {
        try {
            return reader.document(luceneDocId);
        } catch (IOException e) {
            throw new org.opensearch.migrations.bulkload.common.RfsException(
                "Failed to read Solr document at Lucene index location " + luceneDocId, e);
        }
    }

    private static String extractSolrId(org.opensearch.migrations.bulkload.lucene.LuceneDocument document) {
        for (var field : document.getFields()) {
            if ("id".equals(field.name())) {
                var value = field.stringValue();
                if (value == null) value = field.utf8ToStringValue();
                return value;
            }
        }
        return null;
    }
}
