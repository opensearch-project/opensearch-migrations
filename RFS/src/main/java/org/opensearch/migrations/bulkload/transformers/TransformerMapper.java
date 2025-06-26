package org.opensearch.migrations.bulkload.transformers;

import org.opensearch.migrations.UnboundVersionMatchers;
import org.opensearch.migrations.Version;
import org.opensearch.migrations.VersionMatchers;
import org.opensearch.migrations.VersionStrictness;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@AllArgsConstructor
public class TransformerMapper {
    private final Version sourceVersion;
    private final Version targetVersion;

    public Transformer getTransformer(
        int awarenessAttributes,
        MetadataTransformerParams metadataTransformerParams,
        boolean allowLooseMatches
    ) {
        if (allowLooseMatches) {
            log.atInfo()
                .setMessage("Allowing loose version matching, attempting to find matching transformer from {} to {}")
                .addArgument(sourceVersion)
                .addArgument(targetVersion)
                .log();

            return looseTransformerMapping(awarenessAttributes, metadataTransformerParams);
        }

        return strictTransformerMapping(awarenessAttributes, metadataTransformerParams);
    }

    private Transformer strictTransformerMapping(int awarenessAttributes, MetadataTransformerParams metadataTransformerParams) {
        if (VersionMatchers.anyOS.test(targetVersion)) {
            if (VersionMatchers.isES_5_X.test(sourceVersion)) {
                return new Transformer_ES_5_6_to_OS_2_11(awarenessAttributes, metadataTransformerParams);
            }
            if (VersionMatchers.isES_6_X.test(sourceVersion)) {
                return new Transformer_ES_6_8_to_OS_2_11(awarenessAttributes, metadataTransformerParams);
            }
            if (VersionMatchers.equalOrBetween_ES_7_0_and_7_8.test(sourceVersion)) {
                return new Transformer_ES_6_8_to_OS_2_11(awarenessAttributes, metadataTransformerParams);
            }
            if (VersionMatchers.equalOrGreaterThanES_7_9.test(sourceVersion)) {
                return new Transformer_ES_7_10_OS_2_11(awarenessAttributes);
            }
            if (VersionMatchers.isES_8_X.test(sourceVersion)) {
                return new Transformer_ES_7_10_OS_2_11(awarenessAttributes);
            }
            if (VersionMatchers.anyOS.test(sourceVersion)) {
                return new Transformer_ES_7_10_OS_2_11(awarenessAttributes);
            }
        }
        throw new IllegalArgumentException("Unsupported transformation requested for " + sourceVersion + " to " + targetVersion + "." + VersionStrictness.REMEDIATION_MESSAGE);
    }

    private Transformer looseTransformerMapping(int awarenessAttributes, MetadataTransformerParams metadataTransformerParams) {
        if (UnboundVersionMatchers.anyOS.or(UnboundVersionMatchers.isGreaterOrEqualES_6_X).test(targetVersion)) {
            if (UnboundVersionMatchers.isBelowES_6_X.test(sourceVersion)) {
                return new Transformer_ES_5_6_to_OS_2_11(awarenessAttributes, metadataTransformerParams);
            }
            if (VersionMatchers.isES_6_X.test(sourceVersion)) {
                return new Transformer_ES_6_8_to_OS_2_11(awarenessAttributes, metadataTransformerParams);
            }
            if (UnboundVersionMatchers.anyES.or(UnboundVersionMatchers.anyOS).test(sourceVersion)) {
                return new Transformer_ES_7_10_OS_2_11(awarenessAttributes);
            }
        }
        throw new IllegalArgumentException("Unsupported transformation requested for " + sourceVersion + " to " + targetVersion + ".");
    }
}
