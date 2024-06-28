package org.opensearch.migrations.transformation;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * The result after checking if a transformer can be applied to an entity
 */
public abstract class CanApplyResult {
    public final static CanApplyResult YES = new Yes();
    public final static CanApplyResult NO = new No();

   
    public static final class Yes extends CanApplyResult {
    }

    public static final class No extends CanApplyResult {
    }

    /** If the transformation should apply but there is an issue that would prevent it from being applied corrrectly, return a reason */
    @RequiredArgsConstructor
    @Getter
    public static final class Unsupported extends CanApplyResult {
        private final String reason;
    }
}