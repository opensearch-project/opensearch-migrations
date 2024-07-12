package org.opensearch.migrations;

import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collector;
import java.util.stream.Collectors;

public class Utils {
    private Utils() {}

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
