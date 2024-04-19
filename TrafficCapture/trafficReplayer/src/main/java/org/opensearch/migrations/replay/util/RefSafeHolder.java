package org.opensearch.migrations.replay.util;

import io.netty.util.ReferenceCountUtil;
import java.util.Optional;
import javax.annotation.Nullable;

public class RefSafeHolder<T> implements AutoCloseable {
    private final T resource;

    private RefSafeHolder(@Nullable T resource) {
        this.resource = resource;
    }

    static public <T> RefSafeHolder<T> create(@Nullable T resource) {
        return new RefSafeHolder<>(resource);
    }

    public Optional<T> get() {
        return Optional.ofNullable(resource);
    }

    @Override
    public void close() {
        ReferenceCountUtil.release(resource);
    }
}
