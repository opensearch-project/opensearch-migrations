package org.opensearch.migrations.tracing;

import io.opentelemetry.api.common.AttributeKey;
import org.opensearch.migrations.tracing.commoncontexts.IConnectionContext;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

public class AttributeNameMatchingPredicate implements Predicate<AttributeKey> {
    private final boolean negate;
    private final Set<String> keysToMatch;

    public static class Builder {
        private final Set<String> namesSet = new HashSet<>();
        private final boolean negate;
        public Builder(boolean negate) {
            this.negate = negate;
        }
        public Builder add(String name) {
            namesSet.add(name);
            return this;
        }
        public AttributeNameMatchingPredicate build() {
            return new AttributeNameMatchingPredicate(negate, namesSet);
        }
    }

    public static Builder builder(boolean negate) {
        return new Builder(negate);
    }

    private AttributeNameMatchingPredicate(boolean negate, Set<String> keysToMatch) {
        this.negate = negate;
        this.keysToMatch = keysToMatch;
    }

    @Override
    public boolean test(AttributeKey attribute) {
        return keysToMatch.contains(attribute.getKey()) == negate;
    }
}
