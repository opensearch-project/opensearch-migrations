package org.opensearch.migrations.bulkload.common.bulk.metadata;

import java.util.Map;

import org.opensearch.migrations.bulkload.common.bulk.enums.VersionType;
import org.opensearch.migrations.bulkload.pipeline.model.Document;

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

    /**
     * Versioning that preserves a source document's version on the target. {@code external_gte} rather than
     * {@code external} so that RFS retries and work-item re-runs, which resend the same version, are idempotent
     * instead of raising version conflicts.
     */
    public static VersionControlMetadata preserving(long sourceVersion) {
        return VersionControlMetadata.builder()
            .version(sourceVersion)
            .versionType(VersionType.EXTERNAL_GTE)
            .build();
    }

    /**
     * Builds {@link #preserving(long)} metadata from a pipeline {@link Document}'s hints, or returns null
     * when the source did not supply a version.
     */
    public static VersionControlMetadata fromHints(Map<String, String> hints) {
        var version = hints.get(Document.HINT_VERSION);
        return version == null ? null : preserving(Long.parseLong(version));
    }
}
