package org.opensearch.migrations.replay.util;

import io.netty.util.ReferenceCounted;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Function;
import java.util.stream.Stream;

public final class RefSafeStreamUtils {
    public static <T, R extends ReferenceCounted> Stream<R> refSafeMap(Stream<T> inputStream,
        Function<T, R> referenceTrackedMappingFunction) {
        final Deque<R> refCountedTracker = new ArrayDeque<>();
        return inputStream.map(t -> {
            var resource = referenceTrackedMappingFunction.apply(t);
            refCountedTracker.add(resource);
            return resource;
        }).onClose(() -> refCountedTracker.forEach(ReferenceCounted::release));
    }

    private RefSafeStreamUtils() {}
}
