package org.opensearch.migrations.utils;

import java.util.function.Function;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Lombok;
import lombok.NonNull;

public class TrackedFutureJsonFormatter {

    static ObjectMapper objectMapper = new ObjectMapper();

    private TrackedFutureJsonFormatter() {}

    public static <D> String format(TrackedFuture<D, ?> tf) {
        return format(tf, x -> null);
    }

    public static <D> String format(
        TrackedFuture<D, ?> tf,
        @NonNull Function<TrackedFuture<D, ?>, String> resultFormatter
    ) {
        try {
            return objectMapper.writeValueAsString(TrackedFutureMapConverter.makeJson(tf, resultFormatter));
        } catch (Exception e) {
            throw Lombok.sneakyThrow(e);
        }
    }
}
