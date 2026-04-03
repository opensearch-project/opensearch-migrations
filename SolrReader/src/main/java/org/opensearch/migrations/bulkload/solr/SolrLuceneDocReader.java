package org.opensearch.migrations.bulkload.solr;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import org.opensearch.migrations.bulkload.common.DocumentChangeType;
import org.opensearch.migrations.bulkload.common.LuceneDocumentChange;
import org.opensearch.migrations.bulkload.common.ObjectMapperFactory;
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
        DocumentChangeType operation
    ) {
        if (!isLive) {
            return null;
        }

        var document = readDocument(reader, luceneDocId);
        if (document == null) {
            return null;
        }

        String docId = null;
        var fields = new LinkedHashMap<String, Object>();

        for (var field : document.getFields()) {
            if (!isInternalField(field.name())) {
                Object value = extractFieldValue(field);
                if (value != null) {
                    if ("id".equals(field.name())) {
                        docId = value.toString();
                    }
                    addFieldValue(fields, field.name(), value);
                }
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

        return serializeDocument(segmentDocBase + luceneDocId, docId, fields, operation);
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

    @SuppressWarnings("unchecked")
    private static void addFieldValue(LinkedHashMap<String, Object> fields, String name, Object value) {
        var existing = fields.get(name);
        if (existing == null) {
            fields.put(name, value);
        } else if (existing instanceof List) {
            ((List<Object>) existing).add(value);
        } else {
            var list = new ArrayList<>();
            list.add(existing);
            list.add(value);
            fields.put(name, list);
        }
    }

    private static LuceneDocumentChange serializeDocument(
        int docNumber, String docId, LinkedHashMap<String, Object> fields, DocumentChangeType operation
    ) {
        try {
            byte[] sourceBytes = MAPPER.writeValueAsBytes(fields);
            return new LuceneDocumentChange(docNumber, docId, null, sourceBytes, null, operation);
        } catch (Exception e) {
            log.atError().setCause(e).setMessage("Failed to serialize Solr document {}").addArgument(docId).log();
            return null;
        }
    }

    private static boolean isInternalField(String fieldName) {
        return fieldName.startsWith("_");
    }

    private static Object extractFieldValue(LuceneField field) {
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
