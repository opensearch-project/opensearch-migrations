package org.opensearch.migrations.bulkload.transformers;

import java.util.List;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.VersionMatchers;
import org.opensearch.migrations.VersionStrictness;
import org.opensearch.migrations.UnboundVersionMatchers;
import org.opensearch.migrations.transformation.TransformationRule;
import org.opensearch.migrations.transformation.entity.Index;
import org.opensearch.migrations.transformation.rules.IndexMappingTypeRemoval;
import org.opensearch.migrations.transformation.rules.IndexMappingTypeRemovalWithMergedSupport;
import org.opensearch.migrations.transformation.rules.TemplateMatchClausePattern;

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
        Transformer transformer;
        if (allowLooseMatches) {
            log.atInfo()
                .setMessage("Allowing loose version matching, attempting to find matching transformer from {} to {}")
                .addArgument(sourceVersion)
                .addArgument(targetVersion)
                .log();

            transformer = looseTransformerMapping(awarenessAttributes, metadataTransformerParams);
        } else {
            transformer = strictTransformerMapping(awarenessAttributes, metadataTransformerParams);
        }

        log.atInfo()
            .setMessage("Version-mapped transformer class selected: {}")
            .addArgument(transformer.getClass().getSimpleName()).log();

        return transformer;
    }

    private Transformer strictTransformerMapping(int awarenessAttributes, MetadataTransformerParams metadataTransformerParams) {
        if (VersionMatchers.anyOS.test(targetVersion)) {
            return mapSourceVersion(awarenessAttributes, metadataTransformerParams);
        }
        throw new IllegalArgumentException("Unsupported transformation requested for " + sourceVersion + " to " + targetVersion + "." + VersionStrictness.REMEDIATION_MESSAGE);
    }

    private Transformer mapSourceVersion(int awarenessAttributes, MetadataTransformerParams metadataTransformerParams) {
        var rules = getRulesForSourceVersion(metadataTransformerParams);
        return new CanonicalTransformer(awarenessAttributes, rules, rules);
    }

    private List<TransformationRule<Index>> getRulesForSourceVersion(MetadataTransformerParams params) {
        // ES 1.x, 2.x — multi-type with merged support + template match clause
        if (VersionMatchers.isES_2_X.or(VersionMatchers.isES_1_X).test(sourceVersion)) {
            return List.of(
                new IndexMappingTypeRemovalWithMergedSupport(params.getMultiTypeResolutionBehavior()),
                new TemplateMatchClausePattern()
            );
        }
        // ES 5.0-5.4 — multi-type with merged support + template match clause
        if (VersionMatchers.equalOrBetween_ES_5_0_and_5_4.test(sourceVersion)) {
            return List.of(
                new IndexMappingTypeRemovalWithMergedSupport(params.getMultiTypeResolutionBehavior()),
                new TemplateMatchClausePattern()
            );
        }
        // ES 5.5+ — single-type removal + template match clause
        if (VersionMatchers.equalOrGreaterThanES_5_5.test(sourceVersion)) {
            return List.of(
                new IndexMappingTypeRemoval(params.getMultiTypeResolutionBehavior()),
                new TemplateMatchClausePattern()
            );
        }
        // ES 6.x — single-type removal
        if (VersionMatchers.isES_6_X.test(sourceVersion)) {
            return List.of(new IndexMappingTypeRemoval(params.getMultiTypeResolutionBehavior()));
        }
        // ES 7.0-7.8 — single-type removal (still has type wrappers in some cases)
        if (VersionMatchers.equalOrBetween_ES_7_0_and_7_8.test(sourceVersion)) {
            return List.of(new IndexMappingTypeRemoval(params.getMultiTypeResolutionBehavior()));
        }
        // ES 7.9+, ES 8.x, OS — no type removal needed
        if (VersionMatchers.equalOrGreaterThanES_7_9.test(sourceVersion)
            || VersionMatchers.isES_8_X.test(sourceVersion)
            || VersionMatchers.anyOS.test(sourceVersion)) {
            return List.of();
        }
        throw new IllegalArgumentException("Unsupported source version: " + sourceVersion + "." + VersionStrictness.REMEDIATION_MESSAGE);
    }

    private Transformer looseTransformerMapping(int awarenessAttributes, MetadataTransformerParams metadataTransformerParams) {
        if (UnboundVersionMatchers.anyOS.or(UnboundVersionMatchers.isGreaterOrEqualES_6_X).test(targetVersion)) {
            return mapSourceVersionLoose(awarenessAttributes, metadataTransformerParams);
        }
        throw new IllegalArgumentException("Unsupported transformation requested for " + sourceVersion + " to " + targetVersion + ".");
    }

    private Transformer mapSourceVersionLoose(int awarenessAttributes, MetadataTransformerParams metadataTransformerParams) {
        List<TransformationRule<Index>> rules;
        if (UnboundVersionMatchers.isBelowES_5_X.test(sourceVersion)) {
            rules = List.of(
                new IndexMappingTypeRemovalWithMergedSupport(metadataTransformerParams.getMultiTypeResolutionBehavior()),
                new TemplateMatchClausePattern()
            );
        } else if (VersionMatchers.equalOrBetween_ES_5_0_and_5_4.test(sourceVersion)) {
            rules = List.of(
                new IndexMappingTypeRemovalWithMergedSupport(metadataTransformerParams.getMultiTypeResolutionBehavior()),
                new TemplateMatchClausePattern()
            );
        } else if (VersionMatchers.equalOrGreaterThanES_5_5.test(sourceVersion)) {
            rules = List.of(
                new IndexMappingTypeRemoval(metadataTransformerParams.getMultiTypeResolutionBehavior()),
                new TemplateMatchClausePattern()
            );
        } else if (VersionMatchers.isES_6_X.test(sourceVersion)) {
            rules = List.of(new IndexMappingTypeRemoval(metadataTransformerParams.getMultiTypeResolutionBehavior()));
        } else if (UnboundVersionMatchers.anyES.or(UnboundVersionMatchers.anyOS).test(sourceVersion)) {
            rules = List.of();
        } else {
            throw new IllegalArgumentException("Unsupported transformation requested for " + sourceVersion + " to " + targetVersion + ".");
        }
        return new CanonicalTransformer(awarenessAttributes, rules, rules);
    }
}
