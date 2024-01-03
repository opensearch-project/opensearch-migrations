package org.opensearch.migrations.tracing;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

@Getter
public class FilteringAttributeBuilder implements AttributesBuilder {
    private AttributesBuilder underlyingBuilder;
    public final boolean matchExcludes;
    public final Set<String> keysToMatch;

    public FilteringAttributeBuilder(AttributesBuilder underlyingBuilder, boolean matchesExclude,
                                     Set<String> keysToMatch) {
        this.underlyingBuilder = underlyingBuilder;
        this.matchExcludes = matchesExclude;
        this.keysToMatch = Collections.unmodifiableSet(keysToMatch);
    }

    @Override
    public Attributes build() {
        return underlyingBuilder.build();
    }

    @Override
    public <T> AttributesBuilder put(AttributeKey<Long> key, int value) {
        if (keysToMatch.contains(key.getKey()) == matchExcludes) {
            return this;
        }
        underlyingBuilder = underlyingBuilder.put(key, value);
        return this;
    }

    @Override
    public <T> AttributesBuilder put(AttributeKey<T> key, T value) {
        if (keysToMatch.contains(key.getKey()) == matchExcludes) {
            return this;
        }
        underlyingBuilder = underlyingBuilder.put(key, value);
        return this;
    }

    @Override
    public AttributesBuilder putAll(Attributes attributes) {
        attributes.forEach((k,v)->{ this.underlyingBuilder = underlyingBuilder.put((AttributeKey)k,v); });
        return this;
    }
}
