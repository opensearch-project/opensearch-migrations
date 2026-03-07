package org.opensearch.migrations;

import java.time.Duration;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collector;
import java.util.stream.Collectors;

public class Utils {
    private Utils() {}

    /**
     * Format a Duration as a compact human-readable string using whole seconds.
     * Examples: "45s", "3m12s", "2h15m"
     */
    public static String formatDurationInSeconds(Duration d) {
        long totalSeconds = d.getSeconds();
        if (totalSeconds < 0) return "-" + formatDurationInSeconds(d.negated());
        if (totalSeconds < 60) return totalSeconds + "s";
        if (totalSeconds < 3600) return (totalSeconds / 60) + "m" + (totalSeconds % 60) + "s";
        return (totalSeconds / 3600) + "h" + ((totalSeconds % 3600) / 60) + "m";
    }

    /**
     * See https://en.wikipedia.org/wiki/Fold_(higher-order_function)
     */
    public static <A, B> Collector<A, ?, B> foldLeft(
        final B seedValue,
        final BiFunction<? super B, ? super A, ? extends B> f
    ) {
        return Collectors.collectingAndThen(
            Collectors.reducing(Function.<B>identity(), a -> b -> f.apply(b, a), Function::andThen),
            finisherArg -> finisherArg.apply(seedValue)
        );
    }
}
