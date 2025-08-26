package org.opensearch.migrations.bulkload.common.bulk.metadata;

import org.opensearch.migrations.bulkload.common.bulk.enums.VersionType;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

@Data
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class VersionControlMetadata {
    private Long ifPrimaryTerm;
    private Long ifSeqNo;
    private Long version;
    private VersionType versionType;
}
