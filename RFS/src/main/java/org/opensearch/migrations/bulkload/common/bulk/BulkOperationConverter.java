package org.opensearch.migrations.bulkload.common.bulk;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

import org.opensearch.migrations.bulkload.common.ObjectMapperFactory;
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
        Map<String, Object> document;
        try {
            document = doc.source() != null
                ? OBJECT_MAPPER.readValue(doc.source(), new TypeReference<>() {})
                : Map.of();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
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
                .build();
        }
        return IndexOp.builder()
            .operation(IndexOperationMeta.builder()
                .id(doc.id())
                .index(indexName)
                .type(type)
                .routing(routing)
                .build())
            .document(document)
            .build();
    }
}
