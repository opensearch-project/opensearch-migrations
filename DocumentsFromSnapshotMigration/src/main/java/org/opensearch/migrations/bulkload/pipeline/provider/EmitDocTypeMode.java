package org.opensearch.migrations.bulkload.pipeline.provider;

import org.opensearch.migrations.Flavor;
import org.opensearch.migrations.Version;

import lombok.extern.slf4j.Slf4j;

/** Whether the ES {@code _type} field is propagated into bulk action-line metadata. */
@Slf4j
public enum EmitDocTypeMode {
    /** Emit {@code _type} only when the source is ES 6 or older and a doc transformer is configured. */
    AUTO,
    /** Always emit {@code _type}. */
    ON,
    /** Never emit {@code _type}. */
    OFF;

    /**
     * AUTO enables emission for the combination that exercises type-mapping transformers (e.g.
     * TypeMappingSanitizationTransformerProvider) on multi-type indices.
     */
    public boolean resolve(Version sourceVersion, boolean hasDocTransformer) {
        return switch (this) {
            case ON -> true;
            case OFF -> false;
            case AUTO -> {
                boolean isLegacyEs = sourceVersion != null
                    && sourceVersion.getFlavor() == Flavor.ELASTICSEARCH
                    && sourceVersion.getMajor() <= 6;
                boolean enable = isLegacyEs && hasDocTransformer;
                if (enable) {
                    log.atInfo().setMessage("Auto-enabling _type emission for {} with custom transformer")
                        .addArgument(sourceVersion).log();
                }
                yield enable;
            }
        };
    }
}
