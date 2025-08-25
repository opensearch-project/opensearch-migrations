package org.opensearch.migrations.bulkload.common.bulk.operations;

import org.opensearch.migrations.bulkload.common.bulk.enums.IndexOpType;
import org.opensearch.migrations.bulkload.common.bulk.metadata.BaseMetadata;
import org.opensearch.migrations.bulkload.common.bulk.metadata.VersionControlMetadata;
import org.opensearch.migrations.bulkload.common.bulk.metadata.WriteMetadata;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

@Data
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@Jacksonized
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public final class IndexOperationMeta extends BaseMetadata implements BaseOperationMeta {
    @JsonUnwrapped
    private WriteMetadata write;
    
    @JsonUnwrapped
    private VersionControlMetadata versioning;
    
    @JsonProperty("op_type")
    private IndexOpType opType; // optional override included in bulk api schema
}
