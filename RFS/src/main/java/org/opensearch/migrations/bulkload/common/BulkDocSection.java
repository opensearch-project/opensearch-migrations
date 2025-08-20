package org.opensearch.migrations.bulkload.common;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.io.SegmentedStringWriter;
import com.fasterxml.jackson.core.io.SerializedString;
import com.fasterxml.jackson.core.util.BufferRecycler;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;


/**
 * BulkDocSection represents a single document in a bulk request.  It tracks the shape of the document
 * as needed for reindexing, as well as the metadata needed for the bulk request.
 */
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Slf4j
public class BulkDocSection {
    private static final int MAX_STRING_LENGTH = 100 * 1024 * 1024; // ~100 MB

    private static final ObjectMapper OBJECT_MAPPER;
    private static final ObjectMapper BULK_REQUEST_MAPPER;

    static {
        OBJECT_MAPPER = JsonMapper.builder().build();
        OBJECT_MAPPER.getFactory()
                .setStreamReadConstraints(StreamReadConstraints.builder()
                        .maxStringLength(MAX_STRING_LENGTH).build());
        
        SimpleModule module = new SimpleModule();
        module.addSerializer(BulkOperation.class, new BulkOperationSerializer());
        
        BULK_REQUEST_MAPPER = OBJECT_MAPPER.copy()
                .registerModule(module);
    }

    private static final String NEWLINE = "\n";

    @EqualsAndHashCode.Include
    @Getter
    private final String docId;
    private final BulkOperation bulkOperation;

    // Constructor for index operations
    public BulkDocSection(String id, String indexName, String type, String docBody) {
        this(id, indexName, type, docBody, null);
    }

    // Constructor for index operations with routing
    public BulkDocSection(String id, String indexName, String type, String docBody, String routing) {
        this.docId = id;
        this.bulkOperation = new BulkIndex(new Metadata(id, type, indexName, routing), parseSource(docBody));
    }

    // Constructor for delete operations
    public static BulkDocSection createDelete(String id, String indexName, String type) {
        return createDelete(id, indexName, type, null);
    }

    // Constructor for delete operations with routing
    public static BulkDocSection createDelete(String id, String indexName, String type, String routing) {
        return new BulkDocSection(id, indexName, type, routing, true);
    }

    // Private constructor for delete operations
    private BulkDocSection(String id, String indexName, String type, String routing, boolean isDelete) {
        this.docId = id;
        this.bulkOperation = new BulkDelete(new Metadata(id, type, indexName, routing));
    }

    // Constructor from BulkOperation
    private BulkDocSection(BulkOperation bulkOperation) {
        this.docId = bulkOperation.getMetadata().id;
        this.bulkOperation = bulkOperation;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseSource(final String doc) {
        try {
            // This is the string sourceDoc as stored in the Lucene Document
            // This will always be a map based on how lucene works
            return OBJECT_MAPPER.readValue(doc, Map.class);
        } catch (IOException e) {
            throw new DeserializationException("Failed to parse source doc:  " + e.getMessage(), e);
        }
    }

    public static String convertToBulkRequestBody(Collection<BulkDocSection> bulkSections) {
        // Using a single SegmentedStringWriter across all object serializations
        try (SegmentedStringWriter writer = new SegmentedStringWriter(new BufferRecycler())) {
            for (BulkDocSection section : bulkSections) {
                BULK_REQUEST_MAPPER.writeValue(writer, section.bulkOperation);
                writer.append(NEWLINE);
            }
            return writer.getAndClear();
        } catch (IOException e) {
            throw new SerializationException("Failed to serialize ingestion request: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public static BulkDocSection fromMap(Object map) {
        if (!(map instanceof Map)) {
            throw new IllegalArgumentException("Expected a Map but got: " + map.getClass());
        }
        
        Map<String, Object> mapObj = (Map<String, Object>) map;
        
        // Determine the operation type based on the keys in the map
        if (mapObj.containsKey("index")) {
            Map<String, Object> indexData = (Map<String, Object>) mapObj.get("index");
            Map<String, Object> sourceData = (Map<String, Object>) mapObj.get("source");
            
            Metadata metadata = OBJECT_MAPPER.convertValue(indexData, Metadata.class);
            BulkOperation bulkOperation = new BulkIndex(metadata, sourceData);
            return new BulkDocSection(bulkOperation);
        } else if (mapObj.containsKey("delete")) {
            Map<String, Object> deleteData = (Map<String, Object>) mapObj.get("delete");
            
            Metadata metadata = OBJECT_MAPPER.convertValue(deleteData, Metadata.class);
            BulkOperation bulkOperation = new BulkDelete(metadata);
            return new BulkDocSection(bulkOperation);
        } else {
            throw new IllegalArgumentException("Unknown bulk operation type in map: " + mapObj.keySet());
        }
    }

    public long getSerializedLength() {
        try (var stream = new CountingNullOutputStream()) {
            BULK_REQUEST_MAPPER.writeValue(stream, this.bulkOperation);
            return stream.length;
        } catch (IOException e) {
            log.atError().setMessage("Failed to get bulk operation length").setCause(e).log();
            throw new SerializationException("Failed to get bulk operation length " + this.bulkOperation +
                    " from string: " + e.getMessage(), e);
        }
    }

    public String asBulkIndexString() {
        try {
            return BULK_REQUEST_MAPPER.writeValueAsString(this.bulkOperation);
        } catch (IOException e) {
            throw new SerializationException("Failed to write bulk operation " + this.bulkOperation
                    + " from string: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> toMap() {
        return OBJECT_MAPPER.convertValue(bulkOperation, Map.class);
    }

    /**
     * Base class for bulk operations
     */
    private abstract static class BulkOperation {
        abstract Metadata getMetadata();
    }

    /**
     * BulkIndex represents the serialization format of an index operation in a bulk request.
     */
    @NoArgsConstructor(force = true) // For Jackson
    @AllArgsConstructor
    @ToString
    @EqualsAndHashCode
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private static class BulkIndex extends BulkOperation {
        @JsonProperty("index")
        private final Metadata metadata;
        @ToString.Exclude
        @JsonProperty("source")
        private final Map<String, Object> sourceDoc;

        @Override
        Metadata getMetadata() {
            return metadata;
        }
    }

    /**
     * BulkDelete represents the serialization format of a delete operation in a bulk request.
     */
    @NoArgsConstructor(force = true) // For Jackson
    @AllArgsConstructor
    @ToString
    @EqualsAndHashCode
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private static class BulkDelete extends BulkOperation {
        @JsonProperty("delete")
        private final Metadata metadata;

        @Override
        Metadata getMetadata() {
            return metadata;
        }
    }

    /**
     * Metadata for bulk operations
     */
    @NoArgsConstructor(force = true) // For Jackson
    @AllArgsConstructor
    @ToString
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private static class Metadata {
        @JsonProperty("_id")
        private final String id;
        
        private final String type;
        
        @JsonProperty("_index")
        private final String index;
        
        @JsonProperty("routing")
        private final String routing;
        
        // Custom getter to exclude _type when it's "_doc"
        @JsonProperty("_type")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public String getType() {
            // Don't include _type if it's the default value "_doc"
            // This is for compatibility with modern OpenSearch/Elasticsearch versions
            if ("_doc".equals(type)) {
                return null;
            }
            return type;
        }
    }

    /**
     * Custom serializer for BulkOperation to handle the different formats
     */
    public static class BulkOperationSerializer extends JsonSerializer<BulkOperation> {
        @Override
        public void serialize(BulkOperation value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            gen.setRootValueSeparator(new SerializedString(NEWLINE));
            
            if (value instanceof BulkIndex) {
                BulkIndex bulkIndex = (BulkIndex) value;
                // Write the index command
                gen.writeStartObject();
                gen.writePOJOField("index", bulkIndex.metadata);
                gen.writeEndObject();
                // Write the source document on the next line
                gen.writePOJO(bulkIndex.sourceDoc);
            } else if (value instanceof BulkDelete) {
                BulkDelete bulkDelete = (BulkDelete) value;
                // Write only the delete command (no source document)
                gen.writeStartObject();
                gen.writePOJOField("delete", bulkDelete.metadata);
                gen.writeEndObject();
            }
        }
    }

    private static class CountingNullOutputStream extends OutputStream {
        long length = 0;
        @Override
        public void write(int b) {
            length += String.valueOf(b).length();
        }

        @Override
        public void write(byte[] b, int off, int len) {
            Objects.checkFromIndexSize(off, len, b.length);
            length += len;
        }
    }

    public static class DeserializationException extends RuntimeException {
        public DeserializationException(String message, Exception cause) {
            super(message, cause);
        }
    }

    public static class SerializationException extends RuntimeException {
        public SerializationException(String message, Exception cause) {
            super(message, cause);
        }
    }
}
