package org.opensearch.migrations.replay.util;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public class RecursiveImmutableChain<T> {
    public final T item;
    public final RecursiveImmutableChain<T> previous;

    public RecursiveImmutableChain<T> chain(T item) {
        return new RecursiveImmutableChain<>(item, this);
    }
}
