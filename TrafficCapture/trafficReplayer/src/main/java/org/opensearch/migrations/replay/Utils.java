package org.opensearch.migrations.replay;

import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class Utils {
    public static final int MAX_BYTES_SHOWN_FOR_TO_STRING = 128;
    /**
     * See https://en.wikipedia.org/wiki/Fold_(higher-order_function)
     */
    public static <A, B> Collector<A, ?, B>
    foldLeft(final B seedValue, final BiFunction<? super B, ? super A, ? extends B> f) {
        return Collectors.collectingAndThen(
                Collectors.reducing(
                        Function.<B>identity(),
                        a -> b -> f.apply(b, a),
                        Function::andThen),
                finisherArg -> finisherArg.apply(seedValue)
        );
    }

    public static String packetsToStringTruncated(List<byte[]> packetStream) {
        return packetsToStringTruncated(Optional.ofNullable(packetStream).map(p->p.stream()).orElse(null),
                MAX_BYTES_SHOWN_FOR_TO_STRING);
    }

    public static String packetsToStringTruncated(Stream<byte[]> packetStream, int maxBytesToShow) {
        if (packetStream == null) { return "null"; }
        return packetStream.map(bArr-> {
            var str = IntStream.range(0, bArr.length).map(idx -> bArr[idx])
                    .limit(maxBytesToShow)
                    .mapToObj(b -> "" + (char) b)
                    .collect(Collectors.joining());
            return "[" + (bArr.length > maxBytesToShow ? str + "..." : str) + "]";
        })
                .collect(Collectors.joining(","));
    }
}
