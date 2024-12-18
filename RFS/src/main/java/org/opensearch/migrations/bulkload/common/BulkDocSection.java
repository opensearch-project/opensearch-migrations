package org.opensearch.migrations.bulkload.common;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.io.SegmentedStringWriter;
import com.fasterxml.jackson.core.io.SerializedString;
import com.fasterxml.jackson.core.util.BufferRecycler;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Slf4j
public class BulkDocSection {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final ObjectMapper BULK_INDEX_REQUEST_MAPPER = OBJECT_MAPPER.copy()
            .registerModule(new SimpleModule()
                    .addSerializer(BulkIndex.class, new BulkIndex.BulkIndexRequestSerializer()));
    private static final String NEWLINE = "\n";

    @EqualsAndHashCode.Include
    @Getter
    private final String docId;
    private final BulkIndex bulkIndex;

    public BulkDocSection(String id, String indexName, String type, String docBody) {
        this(id, indexName, type, docBody, null);
    }

    public BulkDocSection(String id, String indexName, String type, String docBody, String routing) {
        this.docId = id;
        this.bulkIndex = new BulkIndex(new BulkIndex.Metadata(id, type, indexName, routing), parseSource(docBody));
    }

    private BulkDocSection(BulkIndex bulkIndex) {
        this.docId = bulkIndex.metadata.id;
        this.bulkIndex = bulkIndex;
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
                BULK_INDEX_REQUEST_MAPPER.writeValue(writer, section.bulkIndex);
                writer.append(NEWLINE);
            }
            return writer.getAndClear();
        } catch (IOException e) {
            throw new SerializationException("Failed to serialize ingestion request: " + e.getMessage(), e);
        }
    }

    public static BulkDocSection fromMap(Object map) {
        BulkIndex bulkIndex = OBJECT_MAPPER.convertValue(map, BulkIndex.class);
        return new BulkDocSection(bulkIndex);
    }

    public long getSerializedLength() {
        try (var stream = new CountingNullOutputStream()) {
            BULK_INDEX_REQUEST_MAPPER.writeValue(stream, this.bulkIndex);
            return stream.length;
        } catch (IOException e) {
            log.atError().setMessage("Failed to get bulk index length").setCause(e).log();
            throw new SerializationException("Failed to get bulk index length " + this.bulkIndex +
                    " from string: " + e.getMessage(), e);
        }
    }

    public String asBulkIndexString() {
        try {
            return BULK_INDEX_REQUEST_MAPPER.writeValueAsString(this.bulkIndex);
        } catch (IOException e) {
            throw new SerializationException("Failed to write bulk index " + this.bulkIndex
                    + " from string: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> toMap() {
        return OBJECT_MAPPER.convertValue(bulkIndex, Map.class);
    }

    @NoArgsConstructor(force = true) // For Jackson
    @AllArgsConstructor
    @ToString
    @EqualsAndHashCode
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private static class BulkIndex {
        @JsonProperty("index")
        private final Metadata metadata;
        @ToString.Exclude
        @JsonProperty("source")
        private final Map<String, Object> sourceDoc;

        @NoArgsConstructor(force = true) // For Jackson
        @AllArgsConstructor
        @ToString
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private static class Metadata {
            @JsonProperty("_id")
            private final String id;
            @JsonProperty("_type")
            private final String type;
            @JsonProperty("_index")
            private final String index;
            @JsonProperty("routing")
            private final String routing;
        }

        public static class BulkIndexRequestSerializer extends JsonSerializer<BulkIndex> {
            public static final String BULK_INDEX_COMMAND = "index";
            @Override
            public void serialize(BulkIndex value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                gen.setRootValueSeparator(new SerializedString(NEWLINE));
                gen.writeStartObject();
                gen.writePOJOField(BULK_INDEX_COMMAND, value.metadata);
                gen.writeEndObject();
                gen.writePOJO(value.sourceDoc);
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
