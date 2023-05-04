package org.opensearch.migrations.replay;

import io.netty.buffer.ByteBuf;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opensearch.migrations.replay.datahandlers.IPacketToHttpHandler;
import org.opensearch.migrations.replay.datahandlers.http.HttpJsonTransformerHandler;
import org.opensearch.migrations.transform.JsonTransformer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
public class TransformerTest {

    @Test
    public void testTransformer() throws Exception {
        var referenceStringBuilder = new StringBuilder();
        var numFinalizations = new AtomicInteger();
        // mock object.  values don't matter at all - not what we're testing
        final var dummyAggregatedResponse = new AggregatedRawResponse(17, null, null);
        AtomicInteger decayedMilliseconds = new AtomicInteger(50);
        final int DECAY_FACTOR = 4;
        var transformingHandler = new HttpJsonTransformerHandler(
                JsonTransformer.newBuilder().build(),
                new IPacketToHttpHandler() {
                    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                    @Override
                    public CompletableFuture<Void> consumeBytes(ByteBuf nextRequestPacket) {
                        return CompletableFuture.runAsync(() -> {
                            try {
                                int oldV = decayedMilliseconds.get();
                                int v = oldV / DECAY_FACTOR;
                                Assertions.assertTrue(decayedMilliseconds.compareAndSet(oldV, v));
                                Thread.sleep(decayedMilliseconds.get());
                            } catch (InterruptedException e) {
                                throw new RuntimeException(e);
                            }
                            try {
                                nextRequestPacket.duplicate()
                                        .readBytes(byteArrayOutputStream, nextRequestPacket.readableBytes());
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        });
                    }

                    @Override
                    public CompletableFuture<AggregatedRawResponse> finalizeRequest() {
                        numFinalizations.incrementAndGet();
                        var bytes = byteArrayOutputStream.toByteArray();
                        Assertions.assertEquals(referenceStringBuilder.toString(), new String(bytes, StandardCharsets.UTF_8));
                        return CompletableFuture.completedFuture(dummyAggregatedResponse);
                    }
                });

        Random r = new Random(2);

        var stringParts = IntStream.range(0, 3).mapToObj(i->makeRandomString(r)).map(o->(String)o)
                .collect(Collectors.toList());
        var contentLength = stringParts.stream().mapToInt(s->s.length()).sum();
        var preambleStr = "GET / HTTP/1.1\n" +
                "host: localhost\n" +
                "content-length: " + contentLength + "\n\n";
        var preamble = preambleStr.getBytes(StandardCharsets.UTF_8);
        var allConsumesFuture = stringParts.stream()
                .collect(foldLeft(CompletableFuture.completedFuture(transformingHandler.consumeBytes(preamble)),
                        (cf, s)->cf.thenApply(v->writeStringToBoth(s, referenceStringBuilder, transformingHandler))));

        var innermostFinalizeCallCount = new AtomicInteger();
        var finalizationFuture = allConsumesFuture.thenCompose(v->transformingHandler.finalizeRequest());
        finalizationFuture.whenComplete((arr,t)->{
            Assertions.assertNull(t);
            Assertions.assertNotNull(arr);
            // do nothing but check connectivity between the layers in the bottom most handler
            innermostFinalizeCallCount.incrementAndGet();
            Assertions.assertEquals(dummyAggregatedResponse, arr);
        });
        finalizationFuture.get();
        Assertions.assertEquals(1, innermostFinalizeCallCount.get());
        Assertions.assertEquals(1, numFinalizations.get());
    }

    public static <A, B> Collector<A, ?, B> foldLeft(final B init, final BiFunction<? super B, ? super A, ? extends B> f) {
        return Collectors.collectingAndThen(
                Collectors.reducing(Function.<B>identity(), a -> b -> f.apply(b, a), Function::andThen),
                endo -> endo.apply(init)
        );
    }
    private static String makeRandomString(Random r) {
        return r.ints(r.nextInt(10), 'A', 'Z')
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }

    private static CompletableFuture<Void> writeStringToBoth(String s, StringBuilder referenceStringBuilder,
                                                             HttpJsonTransformerHandler transformingHandler) {
        log.info("Sending string to transformer: "+s);
        referenceStringBuilder.append(s);
        var bytes = s.getBytes(StandardCharsets.UTF_8);
        return transformingHandler.consumeBytes(bytes);
    }
}
