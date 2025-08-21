package org.opensearch.migrations.bulkload.common.bulk;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;
import java.util.Objects;

import org.opensearch.migrations.bulkload.common.ObjectMapperFactory;
import org.opensearch.migrations.bulkload.common.enums.OperationType;
import org.opensearch.migrations.bulkload.common.enums.SchemaVersion;
import org.opensearch.migrations.bulkload.common.operations.BaseOperationMeta;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;
import lombok.experimental.SuperBuilder;

@Data
@NoArgsConstructor
@SuperBuilder
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = "operation_type",
    visible = true
)
@JsonSubTypes({
    @JsonSubTypes.Type(value = IndexOp.class, name = "index"),
    @JsonSubTypes.Type(value = DeleteOp.class, name = "delete")
})
public abstract sealed class BulkOperationSpec permits IndexOp, DeleteOp {
    protected static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.createDefaultMapper();

    @Builder.Default
    private SchemaVersion schema = SchemaVersion.RFS_OPENSEARCH_BULK_V1;
    private Map<String, Object> document;
    private String documentPath;

    @JsonProperty("include_document")
    public abstract boolean isIncludeDocument();

    @JsonProperty("operation_type")
    public abstract OperationType getOperationType();
    
    public abstract BaseOperationMeta getOperation();
    
    /**
     * Calculate the serialized length of this bulk operation in NDJSON format.
     * @return The length in bytes of the serialized operation
     */
    @SneakyThrows(IOException.class)
    public long getSerializedLength() {
        try (var stream = new CountingOutputStream()) {
            BulkNdjson.writeOperation(this, stream, OBJECT_MAPPER);
            return stream.getCount();
        }
    }

    /**
     * Helper class for counting output stream bytes.
     */
    @Getter
    private static class CountingOutputStream extends OutputStream {
        private long count = 0;
        
        @Override
        public void write(int b) {
            count++;
        }
        
        @Override
        public void write(byte[] b, int off, int len) {
            Objects.checkFromIndexSize(off, len, b.length);
            count += len;
        }
    }
}
