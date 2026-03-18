package org.opensearch.migrations.bulkload.solr;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Supplier;

import org.opensearch.migrations.bulkload.common.DocumentChangeType;
import org.opensearch.migrations.bulkload.common.LuceneDocumentChange;
import org.opensearch.migrations.bulkload.common.ObjectMapperFactory;
import org.opensearch.migrations.bulkload.lucene.LuceneDocument;
import org.opensearch.migrations.bulkload.lucene.LuceneField;
import org.opensearch.migrations.bulkload.lucene.LuceneLeafReader;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * Reads Solr documents from Lucene segments. Unlike ES, Solr stores the document ID
 * in a field called {@code id} (not {@code _id}), and has no {@code _source} field.
 * This reader reconstructs JSON from stored fields, handling Solr-specific conventions:
 * <ul>
 *   <li>Boolean fields stored as "T"/"F" → converted to true/false</li>
 *   <li>Multi-valued fields → collected into JSON arrays</li>
 *   <li>Internal fields (_version_, _root_, etc.) → skipped</li>
 * </ul>
 */
@Slf4j
final class SolrLuceneDocReader {

    private static final ObjectMapper MAPPER = ObjectMapperFactory.createDefaultMapper();

    private SolrLuceneDocReader() {}

    static LuceneDocumentChange getDocument(
        LuceneLeafReader reader,
        int luceneDocId,
        boolean isLive,
        int segmentDocBase,
        Supplier<String> getSegmentReaderDebugInfo,
        Path indexDirectoryPath,
        DocumentChangeType operation
    ) {
        if (!isLive) {
            return null;
        }

        LuceneDocument document;
        try {
            document = reader.document(luceneDocId);
        } catch (IOException e) {
            log.atError()
                .setCause(e)
                .setMessage("Failed to read Solr document at Lucene index location {}")
                .addArgument(luceneDocId)
                .log();
            return null;
        }

        String docId = null;
        var fields = new LinkedHashMap<String, Object>();

        for (var field : document.getFields()) {
            String fieldName = field.name();

            if (isInternalField(fieldName)) {
                continue;
            }

            Object value = extractFieldValue(field);
            if (value == null) {
                continue;
            }

            if ("id".equals(fieldName)) {
                docId = value.toString();
            }

            // Handle multi-valued fields: collect into a list
            var existing = fields.get(fieldName);
            if (existing != null) {
                if (existing instanceof List) {
                    @SuppressWarnings("unchecked")
                    var list = (List<Object>) existing;
                    list.add(value);
                } else {
                    var list = new ArrayList<>();
                    list.add(existing);
                    list.add(value);
                    fields.put(fieldName, list);
                }
            } else {
                fields.put(fieldName, value);
            }
        }

        if (docId == null) {
            docId = "solr_doc_" + (segmentDocBase + luceneDocId);
        }

        if (fields.isEmpty()) {
            log.atWarn()
                .setMessage("Solr document {} has no stored fields, skipping")
                .addArgument(docId)
                .log();
            return null;
        }

        try {
            byte[] sourceBytes = MAPPER.writeValueAsBytes(fields);
            return new LuceneDocumentChange(
                segmentDocBase + luceneDocId, docId, null, sourceBytes, null, operation
            );
        } catch (Exception e) {
            log.atError()
                .setCause(e)
                .setMessage("Failed to serialize Solr document {}")
                .addArgument(docId)
                .log();
            return null;
        }
    }

    private static boolean isInternalField(String fieldName) {
        return fieldName.startsWith("_");
    }

    private static Object extractFieldValue(LuceneField field) {
        // Try numeric first (pint, plong, pfloat, pdouble)
        var numValue = field.numericValue();
        if (numValue != null) {
            return numValue;
        }

        String value = field.stringValue();
        if (value == null) {
            value = field.utf8ToStringValue();
        }
        if (value == null) {
            return null;
        }

        // Lucene stores booleans as "T"/"F"
        if ("T".equals(value)) {
            return true;
        }
        if ("F".equals(value)) {
            return false;
        }

        return value;
    }
}
