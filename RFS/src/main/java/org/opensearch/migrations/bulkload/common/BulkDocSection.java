package org.opensearch.migrations.bulkload.common;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.io.SerializedString;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Slf4j
public class BulkDocSection {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    @SuppressWarnings("unchecked")
    private static final ObjectMapper BULK_DOC_COLLECTION_MAPPER = OBJECT_MAPPER.copy()
            .registerModule(new SimpleModule()
                    .addSerializer((Class<Collection<BulkDocSection>>) (Class<?>) Collection.class,
                            new BulkIndexRequestBulkDocSectionCollectionSerializer()));
    private static final ObjectMapper BULK_INDEX_MAPPER = OBJECT_MAPPER.copy()
            .registerModule(new SimpleModule()
                    .addSerializer(BulkIndex.class, new BulkIndex.BulkIndexRequestSerializer()));
    private static final String NEWLINE = "\n";

    @EqualsAndHashCode.Include
    @Getter
    private final String docId;
    private final BulkIndex bulkIndex;

    public BulkDocSection(String id, String indexName, String type, String docBody) {
        this.docId = id;
        this.bulkIndex = new BulkIndex(
            new BulkIndex.Metadata(id, type, indexName),
            parseSource(docBody)
        );
    }

    private BulkDocSection(BulkIndex bulkIndex) {
        this.docId = bulkIndex.metadata.id;
        this.bulkIndex = bulkIndex;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseSource(final String doc) {
        try {
            return OBJECT_MAPPER.readValue(doc, Map.class);
        } catch (IOException e) {
            throw new DeserializationException("Failed to parse source doc:  " + e.getMessage());
        }
    }

    public static String convertToBulkRequestBody(Collection<BulkDocSection> bulkSections) {
        try {
            return BULK_DOC_COLLECTION_MAPPER.writeValueAsString(bulkSections);
        } catch (IOException e) {
            throw new SerializationException("Failed to serialize ingestion request: "+ e.getMessage());
        }
    }

    public String asString() {
        try {
            return BULK_INDEX_MAPPER.writeValueAsString(this.bulkIndex);
        } catch (IOException e) {
            throw new SerializationException("Failed to write bulk index from string: " + e.getMessage());
        }
    }

    public static BulkDocSection fromMap(Map<String, Object> map) {
        BulkIndex bulkIndex = OBJECT_MAPPER.convertValue(map, BulkIndex.class);
        return new BulkDocSection(bulkIndex);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> toMap() {
        return (Map<String, Object>) OBJECT_MAPPER.convertValue(bulkIndex, Map.class);
    }

    @NoArgsConstructor(force = true) // For Jackson
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private static class BulkIndex {
        @JsonProperty("index")
        private final Metadata metadata;
        @JsonProperty("source")
        private final Map<String, Object> sourceDoc;

        @NoArgsConstructor(force = true) // For Jackson
        @AllArgsConstructor
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private static class Metadata {
            @JsonProperty("_id")
            private final String id;
            @JsonProperty("_type")
            private final String type;
            @JsonProperty("_index")
            private final String index;
        }

        public static class BulkIndexRequestSerializer extends JsonSerializer<BulkIndex> {
            public static final String BULK_INDEX_COMMAND = "index";
            @Override
            public void serialize(BulkIndex value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                gen.setRootValueSeparator(new SerializedString(NEWLINE));
                gen.writeStartObject();
                gen.writeObjectField(BULK_INDEX_COMMAND, value.metadata);
                gen.writeEndObject();
                gen.writeObject(value.sourceDoc);
            }
        }
    }

    public static class BulkIndexRequestBulkDocSectionCollectionSerializer extends JsonSerializer<Collection<BulkDocSection>> {
        @Override
        public void serialize(Collection<BulkDocSection> collection, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            gen.setRootValueSeparator(new SerializedString(NEWLINE));
            for (BulkDocSection item : collection) {
                gen.writeObject(item.asString());
            }
        }
    }

    public static class DeserializationException extends RuntimeException {
        public DeserializationException(String message) {
            super(message);
        }
    }

    public static class SerializationException extends RuntimeException {
        public SerializationException(String message) {
            super(message);
        }
    }
}
