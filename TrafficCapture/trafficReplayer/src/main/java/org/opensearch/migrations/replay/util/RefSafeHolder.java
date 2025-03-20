package org.opensearch.migrations.replay.util;

import com.google.errorprone.annotations.MustBeClosed;
import io.netty.util.ReferenceCountUtil;

public class RefSafeHolder<T> implements AutoCloseable {
    private final T resource;

    private RefSafeHolder(T resource) {
        this.resource = resource;
    }

    @MustBeClosed
    public static <T> RefSafeHolder<T> create(T resource) {
        return new RefSafeHolder<>(resource);
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
        return "RefSafeHolder{" + resource + "}";
    }
}
