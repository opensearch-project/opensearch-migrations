package org.opensearch.migrations.bulkload.common.bulk.operations;

import org.opensearch.migrations.bulkload.common.bulk.metadata.BaseMetadata;
import org.opensearch.migrations.bulkload.common.bulk.metadata.VersionControlMetadata;

import com.fasterxml.jackson.annotation.JsonInclude;
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
public final class DeleteOperationMeta extends BaseMetadata implements BaseOperationMeta {
    @JsonUnwrapped
    private VersionControlMetadata versioning;
}
