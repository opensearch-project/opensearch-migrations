package org.opensearch.migrations.bulkload.common.bulk;

import org.opensearch.migrations.bulkload.common.enums.OperationType;
import org.opensearch.migrations.bulkload.common.operations.BaseOperationMeta;
import org.opensearch.migrations.bulkload.common.operations.DeleteOperationMeta;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
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
public final class DeleteOp extends BulkOperationSpec {
    @Builder.Default
    private OperationType operationType = OperationType.DELETE;

    @Builder.Default
    private boolean includeDocument = false;

    private DeleteOperationMeta operation;
    
    @Override
    public OperationType getOperationType() {
        return operationType;
    }
    
    @Override
    public BaseOperationMeta getOperation() {
        return operation;
    }
}
