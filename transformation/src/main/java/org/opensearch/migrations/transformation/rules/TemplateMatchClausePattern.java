package org.opensearch.migrations.transformation.rules;

import org.opensearch.migrations.transformation.CanApplyResult;
import org.opensearch.migrations.transformation.TransformationRule;
import org.opensearch.migrations.transformation.entity.Index;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Supports transformation of the template -> index_patterns that were changed between ES 5 to ES 6
 *
 * Example:
 * Starting state (ES 5):
 * {
 *   "template": "matchingIndexPattern*"
 * }
 *
 * Ending state (ES 6):
 * {
 *   "index_patterns": "matchingIndexPattern*"
 * }
 */
@Slf4j
@AllArgsConstructor
public class TemplateMatchClausePattern implements TransformationRule<Index> {

    public static final String ES5_MATCHING_CLAUSE_KEY = "template";
    public static final String ES6_MATCHING_CLAUSE_KEY = "index_patterns";

    @Override
    public CanApplyResult canApply(final Index index) {
        final var mappingNode = index.getRawJson().get(ES5_MATCHING_CLAUSE_KEY);

        if (mappingNode == null) {
            return CanApplyResult.NO;
        }

        return CanApplyResult.YES;
    }

    @Override
    public boolean applyTransformation(final Index index) {
        if (CanApplyResult.YES != canApply(index)) {
            return false;
        }

        final var rawJson = index.getRawJson();
        final var matchingClauseNode = rawJson.get(ES5_MATCHING_CLAUSE_KEY);

        rawJson.remove(ES5_MATCHING_CLAUSE_KEY);
        rawJson.withArray(ES6_MATCHING_CLAUSE_KEY).add(matchingClauseNode);
        return true;
    }
}
