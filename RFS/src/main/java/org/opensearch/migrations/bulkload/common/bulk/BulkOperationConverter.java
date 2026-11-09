package org.opensearch.migrations.bulkload.common.bulk;

import java.io.IOException;
import java.util.Map;

import org.opensearch.migrations.bulkload.common.ObjectMapperFactory;
import org.opensearch.migrations.bulkload.common.SourceFormat;
import org.opensearch.migrations.bulkload.common.bulk.operations.DeleteOperationMeta;
import org.opensearch.migrations.bulkload.common.bulk.operations.IndexOperationMeta;
import org.opensearch.migrations.bulkload.pipeline.model.Document;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.experimental.UtilityClass;

/**
 * Converts pipeline IR {@link Document} to bulk API {@link BulkOperationSpec}.
 * Single source of truth for this conversion — used by both {@code OpenSearchClient}
 * and {@code OpenSearchDocumentSink}.
 */
@UtilityClass
public class BulkOperationConverter {

    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.createDefaultMapper();

    /**
     * Convert a {@link Document} to a {@link BulkOperationSpec} for the given index.
     */
    public static BulkOperationSpec fromDocument(Document doc, String indexName) {
        return fromDocument(doc, indexName, false);
    }

    /** Build typed metadata for a native transformation, retaining the unparsed source. */
    public static BulkOperationSpec fromRawDocument(Document doc, String indexName) {
        return fromDocument(doc, indexName, true);
    }

    private static BulkOperationSpec fromDocument(Document doc, String indexName, boolean retainRawSource) {
        Map<String, Object> document;
        if (doc.source() == null) {
            document = Map.of();
        } else if (retainRawSource) {
            document = null;
        } else {
            document = parseSource(doc);
        }

        String routing = doc.hints().get(Document.HINT_ROUTING);
        String type = doc.hints().get(Document.HINT_TYPE);

        if (doc.operation() == Document.Operation.DELETE) {
            return DeleteOp.builder()
                .operation(DeleteOperationMeta.builder()
                    .id(doc.id())
                    .index(indexName)
                    .type(type)
                    .routing(routing)
                    .build())
                .document(document)
                .rawDocument(retainRawSource ? doc.source() : null)
                .originalSource(document)
                .originalSourceBytes(retainRawSource ? doc.source() : null)
                .sourceHints(doc.hints())
                .sourceMetadata(doc.sourceMetadata())
                .build();
        }
        return IndexOp.builder()
            .operation(indexMetadata(doc, indexName, false))
            .document(document)
            .rawDocument(retainRawSource ? doc.source() : null)
            .originalSource(document)
            .originalSourceBytes(retainRawSource ? doc.source() : null)
            .sourceHints(doc.hints())
            .sourceMetadata(doc.sourceMetadata())
            .build();
    }

    private static Map<String, Object> parseSource(Document doc) {
        try {
            return OBJECT_MAPPER.readValue(SourceFormat.toJson(doc.source()), new TypeReference<>() {});
        } catch (IOException | SourceFormat.InvalidDocumentSourceException e) {
            if (doc.operation() == Document.Operation.DELETE) {
                // A delete sends no body, so an unreadable prior source (kept for diagnostics) must not block it.
                return Map.of();
            }
            throw e instanceof SourceFormat.InvalidDocumentSourceException invalid ? invalid
                : new SourceFormat.InvalidDocumentSourceException("Document source is not valid JSON", e);
        }
    }

    /**
     * Build index action metadata without parsing the document body.
     * Versioning is added only by an explicitly configured transformation.
     */
    public static IndexOperationMeta indexMetadata(Document doc, String indexName, boolean stripIds) {
        String id = stripIds ? null : doc.id();
        return IndexOperationMeta.builder()
            .id(id)
            .index(indexName)
            .type(doc.hints().get(Document.HINT_TYPE))
            .routing(doc.hints().get(Document.HINT_ROUTING))
            .build();
    }
}
