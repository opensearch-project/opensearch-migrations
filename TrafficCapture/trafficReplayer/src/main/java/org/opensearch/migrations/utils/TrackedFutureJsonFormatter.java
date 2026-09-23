package org.opensearch.migrations.utils;

// REBUILD-LIMBO(G11) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Carried verbatim. This was the pre-rebuild implementation of a responsibility the design
// reassigns, so it is the input to that refactor rather than something to re-derive. Resolve it to
// dead, keep, or refactor deliberately -- see AGENTS.md section 8a, and read this before writing

// REBUILD-LIMBO-START(G11)
/*

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

*/
// REBUILD-LIMBO-END(G11)