package org.opensearch.migrations.bulkload.solr;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.function.Supplier;

import org.opensearch.migrations.bulkload.common.DocumentChangeType;
import org.opensearch.migrations.bulkload.common.LuceneDocumentChange;
import org.opensearch.migrations.bulkload.common.ObjectMapperFactory;
import org.opensearch.migrations.bulkload.lucene.LuceneDocument;
import org.opensearch.migrations.bulkload.lucene.LuceneLeafReader;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * Reads Solr documents from Lucene segments. Unlike ES, Solr stores the document ID
 * in a field called {@code id} (not {@code _id}), and has no {@code _source} field.
 * This reader reconstructs JSON from stored fields.
 */
@Slf4j
final class SolrLuceneDocReader {

    private static final ObjectMapper MAPPER = ObjectMapperFactory.createDefaultMapper();

    private SolrLuceneDocReader() {}

    /**
     * Read a Solr document from a Lucene segment, reconstructing _source from stored fields.
     */
    static LuceneDocumentChange getDocument(
        LuceneLeafReader reader, int luceneDocId, boolean isLive, int segmentDocBase,
        Supplier<String> getSegmentReaderDebugInfo, Path indexDirectoryPath, DocumentChangeType operation
    ) {
        if (!isLive) {
            return null;
        }

        LuceneDocument document;
        try {
            document = reader.document(luceneDocId);
        } catch (IOException e) {
            log.atError().setCause(e).setMessage("Failed to read Solr document at Lucene index location {}")
                .addArgument(luceneDocId).log();
            return null;
        }

        String docId = null;
        var fields = new LinkedHashMap<String, Object>();

        for (var field : document.getFields()) {
            String fieldName = field.name();

            // Skip Solr/Lucene internal fields
            if (fieldName.startsWith("_") && !"_version_".equals(fieldName)) {
                continue;
            }
            if ("_version_".equals(fieldName)) {
                continue; // Skip Solr's internal version field
            }

            String value = field.stringValue();
            if (value == null) {
                value = field.utf8ToStringValue();
            }
            if (value == null) {
                // Try numeric
                var numValue = field.numericValue();
                if (numValue != null) {
                    fields.put(fieldName, numValue);
                    if ("id".equals(fieldName)) {
                        docId = numValue.toString();
                    }
                    continue;
                }
                continue;
            }

            if ("id".equals(fieldName)) {
                docId = value;
            }
            fields.put(fieldName, value);
        }

        if (docId == null) {
            docId = "solr_doc_" + (segmentDocBase + luceneDocId);
        }

        if (fields.isEmpty()) {
            log.atWarn().setMessage("Solr document {} has no stored fields, skipping")
                .addArgument(docId).log();
            return null;
        }

        try {
            byte[] sourceBytes = MAPPER.writeValueAsBytes(fields);
            return new LuceneDocumentChange(
                segmentDocBase + luceneDocId, docId, null, sourceBytes, null, operation
            );
        } catch (Exception e) {
            log.atError().setCause(e).setMessage("Failed to serialize Solr document {}")
                .addArgument(docId).log();
            return null;
        }
    }
}
