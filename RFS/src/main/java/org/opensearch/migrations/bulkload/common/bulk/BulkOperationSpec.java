package org.opensearch.migrations.bulkload.common.bulk;

import java.util.Map;

import org.opensearch.migrations.bulkload.common.bulk.enums.OperationType;
import org.opensearch.migrations.bulkload.common.bulk.enums.SchemaVersion;
import org.opensearch.migrations.bulkload.common.bulk.operations.BaseOperationMeta;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
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
    protected static final String OPERATION_TYPE_KEY = "operation_type";
    protected static final String INCLUDE_DOCUMENT_KEY = "include_document";

    @Builder.Default
    private SchemaVersion schema = SchemaVersion.RFS_OPENSEARCH_BULK_V1;
    private Map<String, Object> document;
    private String documentPath;

    /**
     * Original (pre-transformation) source document, captured at conversion time so DLQ
     * records can carry the source-index document rather than the transformed one. This
     * is never serialized to the bulk request wire format or exposed to the transformer;
     * it is purely an in-memory side-channel for failure reporting.
     */
    @JsonIgnore
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private transient Map<String, Object> originalSource;

    @JsonProperty(INCLUDE_DOCUMENT_KEY)
    public abstract boolean isIncludeDocument();

    @JsonProperty(OPERATION_TYPE_KEY)
    public abstract OperationType getOperationType();

    public abstract BaseOperationMeta getOperation();
}
