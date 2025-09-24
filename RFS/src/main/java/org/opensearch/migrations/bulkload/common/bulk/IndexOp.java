package org.opensearch.migrations.bulkload.common.bulk;

import org.opensearch.migrations.bulkload.common.bulk.enums.OperationType;
import org.opensearch.migrations.bulkload.common.bulk.operations.IndexOperationMeta;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

@Data
@NoArgsConstructor
@SuperBuilder
@Jacksonized
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public final class IndexOp extends BulkOperationSpec {
    public static final String OP_TYPE_VALUE = "index";
    public static final OperationType OP_TYPE = OperationType.INDEX;

    @Builder.Default
    private boolean includeDocument = true;

    @Override
    public OperationType getOperationType() {
        return OP_TYPE;
    }

    @Getter
    private IndexOperationMeta operation;
}
