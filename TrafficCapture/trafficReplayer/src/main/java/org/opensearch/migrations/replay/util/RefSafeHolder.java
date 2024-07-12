package org.opensearch.migrations.replay.util;

import javax.annotation.Nullable;

import com.google.errorprone.annotations.MustBeClosed;

import io.netty.util.ReferenceCountUtil;

public class RefSafeHolder<T> implements AutoCloseable {
    private final T resource;

    private RefSafeHolder(@Nullable T resource) {
        this.resource = resource;
    }

    @MustBeClosed
    static public <T> RefSafeHolder<T> create(@Nullable T resource) {
        return new RefSafeHolder<>(resource);
    }

    public @Nullable T get() {
        return resource;
    }

    @Override
    public void close() {
        ReferenceCountUtil.release(resource);
    }
}
