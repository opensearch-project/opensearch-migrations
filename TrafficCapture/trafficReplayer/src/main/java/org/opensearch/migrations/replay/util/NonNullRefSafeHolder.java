package org.opensearch.migrations.replay.util;

import com.google.errorprone.annotations.MustBeClosed;

import io.netty.util.ReferenceCountUtil;
import lombok.NonNull;

public class NonNullRefSafeHolder<T> implements AutoCloseable {
    private final T resource;

    @MustBeClosed
    private NonNullRefSafeHolder(T resource) {
        this.resource = resource;
    }

    @MustBeClosed
    public static <T> NonNullRefSafeHolder<T> create(@NonNull T resource) {
        return new NonNullRefSafeHolder<>(resource);
    }

    public T get() {
        return resource;
    }

    @Override
    public void close() {
        ReferenceCountUtil.release(resource);
    }

    @Override
    public String toString() {
        return "NonNullRefSafeHolder{" + resource + "}";
    }
}
