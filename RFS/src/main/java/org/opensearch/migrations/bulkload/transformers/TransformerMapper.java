package org.opensearch.migrations.bulkload.transformers;

import java.util.List;

import org.opensearch.migrations.UnboundVersionMatchers;
import org.opensearch.migrations.Version;
import org.opensearch.migrations.VersionMatchers;
import org.opensearch.migrations.VersionStrictness;
import org.opensearch.migrations.transformation.TransformationRule;
import org.opensearch.migrations.transformation.entity.Index;
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
        boolean allowLooseMatches
    ) {
        if (allowLooseMatches) {
            log.atInfo()
                .setMessage("Allowing loose version matching, attempting to find matching transformer from {} to {}")
                .addArgument(sourceVersion)
                .addArgument(targetVersion)
                .log();
        }

        validateTargetVersion(allowLooseMatches);
        var rules = resolveRules(allowLooseMatches);
        var transformer = new CanonicalTransformer(awarenessAttributes, rules, rules);

        log.atInfo()
            .setMessage("Version-mapped transformer class selected: {}")
            .addArgument(transformer.getClass().getSimpleName()).log();

        return transformer;
    }

    private void validateTargetVersion(boolean allowLooseMatches) {
        if (allowLooseMatches) {
            if (!UnboundVersionMatchers.anyOS.or(UnboundVersionMatchers.isGreaterOrEqualES_6_X).test(targetVersion)) {
                throw new IllegalArgumentException(
                    "Unsupported transformation requested for " + sourceVersion + " to " + targetVersion + ".");
            }
        } else {
            if (!VersionMatchers.anyOS.test(targetVersion)) {
                throw new IllegalArgumentException(
                    "Unsupported transformation requested for " + sourceVersion + " to " + targetVersion + "."
                    + VersionStrictness.REMEDIATION_MESSAGE);
            }
        }
    }

    private List<TransformationRule<Index>> resolveRules(boolean allowLooseMatches) {
        // Multi-type mapping resolution (ES <7 multiple types per index) is now handled by the
        // auto-applied TypeMappingsSanitizationTransformer custom transform, which unions types
        // by default and runs ahead of these version rules. The legacy IndexMappingTypeRemoval /
        // IndexMappingTypeRemovalWithMergedSupport rules are no longer wired into the metadata
        // path (see MetadataTransformationRegistry + MigratorEvaluatorBase.selectTransformer).

        // ES 1.x-5.4 (strict ES 1.x/2.x; loose below ES 5.x; plus ES 5.0-5.4) — template clause shape fix
        if ((allowLooseMatches
                ? UnboundVersionMatchers.isBelowES_5_X.test(sourceVersion)
                : VersionMatchers.isES_2_X.or(VersionMatchers.isES_1_X).test(sourceVersion))
            || VersionMatchers.equalOrBetween_ES_5_0_and_5_4.test(sourceVersion)) {
            return List.of(new TemplateMatchClausePattern());
        }
        // ES 5.5+ — template match clause shape fix
        if (VersionMatchers.equalOrGreaterThanES_5_5.test(sourceVersion)) {
            return List.of(new TemplateMatchClausePattern());
        }
        // ES 6.x — type union handled by the custom transform; nothing version-specific here
        if (VersionMatchers.isES_6_X.test(sourceVersion)) {
            return List.of();
        }
        // ES 7.0-7.8 — type union handled by the custom transform (strict only; loose falls through)
        if (!allowLooseMatches && VersionMatchers.equalOrBetween_ES_7_0_and_7_8.test(sourceVersion)) {
            return List.of();
        }
        // ES 7.9+, 8.x, 9.x, OS — no type removal needed
        if (allowLooseMatches
            ? UnboundVersionMatchers.anyES.or(UnboundVersionMatchers.anyOS).test(sourceVersion)
            : (VersionMatchers.equalOrGreaterThanES_7_9.test(sourceVersion)
                || VersionMatchers.isES_8_X.test(sourceVersion)
                || VersionMatchers.isES_9_X.test(sourceVersion)
                || VersionMatchers.anyOS.test(sourceVersion))) {
            return List.of();
        }
        // Solr — no type removal needed
        if (UnboundVersionMatchers.anySolr.test(sourceVersion)) {
            return List.of();
        }
        throw new IllegalArgumentException(
            "Unsupported source version: " + sourceVersion + "." + VersionStrictness.REMEDIATION_MESSAGE);
    }
}
