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
        log.atInfo()
            .setMessage("=== TRANSFORMER SELECTION DEBUG ===")
            .log();
        log.atInfo()
            .setMessage("Source version: {} (flavor: {}, major: {}, minor: {})")
            .addArgument(sourceVersion)
            .addArgument(sourceVersion.getFlavor())
            .addArgument(sourceVersion.getMajor())
            .addArgument(sourceVersion.getMinor())
            .log();
        log.atInfo()
            .setMessage("Target version: {} (flavor: {}, major: {}, minor: {})")
            .addArgument(targetVersion)
            .addArgument(targetVersion.getFlavor())
            .addArgument(targetVersion.getMajor())
            .addArgument(targetVersion.getMinor())
            .log();
        log.atInfo()
            .setMessage("Allow loose matches: {}")
            .addArgument(allowLooseMatches)
            .log();
        
        Transformer transformer;
        if (allowLooseMatches) {
            log.atInfo()
                .setMessage("Using loose version matching to find transformer from {} to {}")
                .addArgument(sourceVersion)
                .addArgument(targetVersion)
                .log();

            transformer = looseTransformerMapping(awarenessAttributes, metadataTransformerParams);
        } else {
            log.atInfo()
                .setMessage("Using strict version matching to find transformer from {} to {}")
                .addArgument(sourceVersion)
                .addArgument(targetVersion)
                .log();
            transformer = strictTransformerMapping(awarenessAttributes, metadataTransformerParams);
        }

        log.atInfo()
            .setMessage("=== SELECTED TRANSFORMER: {} ===")
            .addArgument(transformer.getClass().getSimpleName())
            .log();
        log.atInfo()
            .setMessage("Transformer details: {}")
            .addArgument(transformer.toString())
            .log();

        return transformer;
    }

    private Transformer strictTransformerMapping(int awarenessAttributes, MetadataTransformerParams metadataTransformerParams) {
        if (VersionMatchers.anyOS.test(targetVersion)) {
            return mapStrictSourceVersion(awarenessAttributes, metadataTransformerParams);
        }
        throw new IllegalArgumentException("Unsupported transformation requested for " + sourceVersion + " to " + targetVersion + "." + VersionStrictness.REMEDIATION_MESSAGE);
    }

    private Transformer mapStrictSourceVersion(int awarenessAttributes, MetadataTransformerParams metadataTransformerParams) {
        if (VersionMatchers.isES_2_X.or(VersionMatchers.isES_1_X).test(sourceVersion)) {
            return new Transformer_ES_2_4_to_OS_2_19(awarenessAttributes, metadataTransformerParams);
        }
        if (VersionMatchers.equalOrBetween_ES_5_0_and_5_4.test(sourceVersion)) {
            return new Transformer_ES_5_4_to_OS_2_19(awarenessAttributes, metadataTransformerParams);
        }
        if (VersionMatchers.equalOrGreaterThanES_5_5.test(sourceVersion)) {
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
        throw new IllegalArgumentException("Unsupported transformation requested for " + sourceVersion + " to " + targetVersion + "." + VersionStrictness.REMEDIATION_MESSAGE);
    }

    private Transformer looseTransformerMapping(int awarenessAttributes, MetadataTransformerParams metadataTransformerParams) {
        if (UnboundVersionMatchers.anyOS.or(UnboundVersionMatchers.isGreaterOrEqualES_6_X).test(targetVersion)) {
            if (UnboundVersionMatchers.isBelowES_5_X.test(sourceVersion)) {
                return new Transformer_ES_2_4_to_OS_2_19(awarenessAttributes, metadataTransformerParams);
            }
            if (VersionMatchers.equalOrBetween_ES_5_0_and_5_4.test(sourceVersion)) {
                return new Transformer_ES_5_4_to_OS_2_19(awarenessAttributes, metadataTransformerParams);
            }
            if (VersionMatchers.equalOrGreaterThanES_5_5.test(sourceVersion)) {
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
