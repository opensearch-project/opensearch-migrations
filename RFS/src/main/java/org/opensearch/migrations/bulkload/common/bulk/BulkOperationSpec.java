package org.opensearch.migrations.bulkload.common.bulk;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

import org.opensearch.migrations.bulkload.common.ObjectMapperFactory;
import org.opensearch.migrations.bulkload.common.bulk.enums.OperationType;
import org.opensearch.migrations.bulkload.common.bulk.enums.SchemaVersion;
import org.opensearch.migrations.bulkload.common.bulk.operations.BaseOperationMeta;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@Data
@NoArgsConstructor
@SuperBuilder
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = BulkOperationSpec.OPERATION_TYPE_KEY,
    visible = true
)
@JsonSubTypes({
    @JsonSubTypes.Type(value = IndexOp.class, name = IndexOp.OP_TYPE_VALUE),
    @JsonSubTypes.Type(value = DeleteOp.class, name = DeleteOp.OP_TYPE_VALUE)
})
public abstract sealed class BulkOperationSpec permits IndexOp, DeleteOp {
    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.createDefaultMapper();
    protected static final String OPERATION_TYPE_KEY = "operation_type";
    protected static final String INCLUDE_DOCUMENT_KEY = "include_document";

    @Builder.Default
    private SchemaVersion schema = SchemaVersion.RFS_OPENSEARCH_BULK_V1;
    private Map<String, Object> document;
    private String documentPath;

    /**
     * Unparsed body for native transformations. Metadata-only changes retain these
     * bytes through serialization and retries. Accessing {@link #getDocument()}
     * materializes a mutable Map and switches subsequent writes to that Map.
     * Treat the bytes as immutable; replace them with {@link #setRawDocument(byte[])}
     * to preserve the original source for failure reporting.
     */
    @JsonIgnore
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private transient byte[] rawDocument;

    /**
     * Original document hints available to transformations, including keys unknown
     * to the OpenSearch sink. These are input context, not outgoing action fields:
     * use the typed operation metadata to change routing, index, or other bulk fields.
     * {@link BulkNdjson} never serializes this map to the bulk request.
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, String> sourceHints;

    /**
     * Source metadata exposed to document transformations, such as the snapshot's
     * {@code _version}. This is separate from the operation metadata that controls
     * target writes, so transformations can remove or override the operation's
     * version without losing the original snapshot version.
     * {@link BulkNdjson} only serializes the operation and document body.
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, Object> sourceMetadata;

    /**
     * Original (pre-transformation) source document, captured at conversion time so failed document stream
     * records can carry the source-index document rather than the transformed one. This
     * is never serialized to the bulk request wire format or exposed to the transformer;
     * it is purely an in-memory side-channel for failure reporting.
     */
    @JsonIgnore
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private transient Map<String, Object> originalSource;

    /** Retain the source for failure reporting without parsing it on successful writes. */
    @JsonIgnore
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private transient byte[] originalSourceBytes;

    public Map<String, Object> getDocument() {
        if (rawDocument != null) {
            document = parseSource(rawDocument);
            rawDocument = null;
        }
        return document;
    }

    public void setDocument(Map<String, Object> document) {
        this.document = document;
        this.rawDocument = null;
    }

    public void setRawDocument(byte[] rawDocument) {
        this.rawDocument = rawDocument;
        this.document = null;
    }

    public Map<String, Object> getOriginalSource() {
        if (originalSourceBytes != null) {
            originalSource = parseSource(originalSourceBytes);
            originalSourceBytes = null;
        }
        return originalSource;
    }

    public void setOriginalSource(Map<String, Object> originalSource) {
        this.originalSource = originalSource;
        this.originalSourceBytes = null;
    }

    private static Map<String, Object> parseSource(byte[] source) {
        try {
            return OBJECT_MAPPER.readValue(source, new TypeReference<>() {});
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @JsonProperty(INCLUDE_DOCUMENT_KEY)
    public abstract boolean isIncludeDocument();

    @JsonProperty(OPERATION_TYPE_KEY)
    public abstract OperationType getOperationType();

    public abstract BaseOperationMeta getOperation();

    /**
     * Expose action versions as decimal strings to preserve long precision through
     * JavaScript, including scripts that copy or serialize the operation metadata.
     * The bulk wire format still serializes {@code VersionControlMetadata.version}
     * as a JSON integer.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> toTransformerMap(ObjectMapper mapper) {
        Map<String, Object> result = mapper.convertValue(this, Map.class);
        var operation = (Map<String, Object>) result.get("operation");
        if (operation != null && operation.get("version") != null) {
            operation.put("version", operation.get("version").toString());
        }
        return result;
    }
}
