package org.opensearch.migrations.replay;

import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.datahandlers.IPacketToHttpHandler;

import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collector;
import java.util.stream.Collectors;

@Slf4j
public class TestUtils {
    public static <A, B> Collector<A, ?, B> foldLeft(final B init, final BiFunction<? super B, ? super A, ? extends B> f) {
        return Collectors.collectingAndThen(
                Collectors.reducing(Function.<B>identity(), a -> b -> f.apply(b, a), Function::andThen),
                finisherArg -> finisherArg.apply(init)
        );
    }

    static String resolveReferenceString(StringBuilder referenceStringBuilder) {
        return resolveReferenceString(referenceStringBuilder, List.of());
    }

        static String resolveReferenceString(StringBuilder referenceStringBuilder,
                                         Collection<AbstractMap.SimpleEntry<String,String>> replacementMappings) {
        for (var kvp : replacementMappings) {
            var idx = referenceStringBuilder.indexOf(kvp.getKey());
            referenceStringBuilder.replace(idx, idx + kvp.getKey().length(), kvp.getValue());
        }
        return referenceStringBuilder.toString();
    }

    static String makeRandomString(Random r, int maxStringSize) {
        return r.ints(r.nextInt(maxStringSize), 'A', 'Z')
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }

    static CompletableFuture<Void> writeStringToBoth(String s, StringBuilder referenceStringBuilder,
                                                     IPacketToHttpHandler transformingHandler) {
        log.info("Sending string to transformer: "+s);
        referenceStringBuilder.append(s);
        var bytes = s.getBytes(StandardCharsets.UTF_8);
        return transformingHandler.consumeBytes(bytes);
    }

    static CompletableFuture<Void> chainedWriteHeadersAndDualWritePayloadParts(IPacketToHttpHandler packetConsumer,
                                                                               List<String> stringParts,
                                                                               StringBuilder referenceStringAccumulator,
                                                                               String headers) {
        return stringParts.stream().collect(
                foldLeft(packetConsumer.consumeBytes(headers.getBytes(StandardCharsets.UTF_8)),
                        (cf, s) -> cf.thenCompose(v -> writeStringToBoth(s, referenceStringAccumulator, packetConsumer))));
    }

    static CompletableFuture<Void>
    chainedDualWriteHeaderAndPayloadParts(IPacketToHttpHandler packetConsumer,
                                          List<String> stringParts,
                                          StringBuilder referenceStringAccumulator,
                                          Function<Integer, String> headersGenerator) {
        var contentLength = stringParts.stream().mapToInt(s->s.length()).sum();
        String headers = headersGenerator.apply(contentLength) + "\n";
        referenceStringAccumulator.append(headers);
        return chainedWriteHeadersAndDualWritePayloadParts(packetConsumer, stringParts, referenceStringAccumulator, headers);
    }
}
