package org.opensearch.migrations.bulkload.solr;

import java.io.IOException;

import org.opensearch.migrations.bulkload.common.DocumentChangeType;
import org.opensearch.migrations.bulkload.common.LuceneDocumentChange;
import org.opensearch.migrations.bulkload.lucene.FieldMappingContext;
import org.opensearch.migrations.bulkload.lucene.LuceneLeafReader;
import org.opensearch.migrations.bulkload.lucene.StoredFieldDocReader;

import lombok.extern.slf4j.Slf4j;

/**
 * Reads Solr documents from Lucene segments using the shared {@link StoredFieldDocReader}.
 * Unlike ES, Solr stores the document ID in a field called {@code id} (not {@code _id}),
 * and has no {@code _source} field — all documents are reconstructed from stored fields.
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
        if (document == null) {
            return null;
        }

        var result = StoredFieldDocReader.readStoredFields(
            document, "id", SolrLuceneDocReader::isInternalField, mappingContext);

        String docId = result.documentId();
        if (docId == null) {
            docId = "solr_doc_" + (segmentDocBase + luceneDocId);
        }

        var fields = result.fields();
        if (fields.isEmpty()) {
            log.atWarn()
                .setMessage("Solr document {} has no stored fields, skipping")
                .addArgument(docId)
                .log();
            return null;
        }

        byte[] sourceBytes = StoredFieldDocReader.toJsonBytes(fields);
        return new LuceneDocumentChange(segmentDocBase + luceneDocId, docId, null, sourceBytes, null, operation);
    }

    private static org.opensearch.migrations.bulkload.lucene.LuceneDocument readDocument(
        LuceneLeafReader reader, int luceneDocId
    ) {
        try {
            return reader.document(luceneDocId);
        } catch (IOException e) {
            log.atError()
                .setCause(e)
                .setMessage("Failed to read Solr document at Lucene index location {}")
                .addArgument(luceneDocId)
                .log();
            return null;
        }
    }

    private static boolean isInternalField(String fieldName) {
        return fieldName.startsWith("_");
    }
}
