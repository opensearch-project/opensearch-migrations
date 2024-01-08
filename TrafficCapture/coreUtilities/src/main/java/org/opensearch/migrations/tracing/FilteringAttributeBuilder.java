package org.opensearch.migrations.tracing;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import lombok.Getter;

import java.util.Collections;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The use-case of filtering attributes in instruments might be better to implement via views.
 */
@Getter
public class FilteringAttributeBuilder implements AttributesBuilder {
    private AttributesBuilder underlyingBuilder;
    private final Predicate<AttributeKey> excludePredicate;

    public FilteringAttributeBuilder(AttributesBuilder underlyingBuilder, Predicate<AttributeKey> excludePredicate) {
        this.underlyingBuilder = underlyingBuilder;
        this.excludePredicate = excludePredicate;
    }

    @Override
    public Attributes build() {
        return underlyingBuilder.build();
    }

    @Override
    public <T> AttributesBuilder put(AttributeKey<Long> key, int value) {
        if (excludePredicate.test(key)) {
            return this;
        }
        underlyingBuilder = underlyingBuilder.put(key, value);
        return this;
    }

    @Override
    public <T> AttributesBuilder put(AttributeKey<T> key, T value) {
        if (excludePredicate.test(key)) {
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
