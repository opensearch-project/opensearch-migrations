package org.opensearch.migrations.trafficcapture.proxyserver.netty;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;

import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@WrapWithNettyLeakDetection(repetitions = 1)
@Slf4j
class HeaderRemoverHandlerTest {

    private static final int NUM_RANDOM_RUNS = 1_000;

    private void runTestsWithSize(BiFunction<Boolean, String, String> msgMaker, Supplier<IntStream> sizesSupplier) {
        log.atDebug().setMessage("sizes: {}")
            .addArgument(() -> sizesSupplier.get().limit(16).mapToObj(i->""+i).collect(Collectors.joining(",")))
            .log();
        runTestWithSize(b -> msgMaker.apply(b,"\n"), sizesSupplier.get());
        runTestWithSize(b -> msgMaker.apply(b, "\r\n"), sizesSupplier.get());
    }

    public void runTestWithSize(Function<Boolean,String> messageMaker, IntStream sizes) {
        final var sourceMsg = messageMaker.apply(true);

        var channel = new EmbeddedChannel(new HeaderRemoverHandler("host:"));
        HeaderAdderHandlerTest.sliceMessageIntoChannelWrites(channel, sourceMsg, sizes);
        var outputBuf = channel.alloc().compositeBuffer();
        channel.inboundMessages().forEach(v -> outputBuf.addComponent(true, ((ByteBuf) v).retain()));
        channel.finishAndReleaseAll();

        var outputString = outputBuf.toString(StandardCharsets.UTF_8);
        Assertions.assertEquals(messageMaker.apply(false), outputString,
            "Error converting source message: " + sourceMsg);
        outputBuf.release();
    }

    @Test
    public void newlinesArePreserved() {
        runTestsWithSize((b,s) -> "GET / HTTP/1.1\r\n" + (b ? "host: localhost\r\n" : "") + "\r\n",
            () -> IntStream.of(Integer.MAX_VALUE));
    }

    @Test
    public void throwsOnHostFormatError() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> new HeaderRemoverHandler("host"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new HeaderRemoverHandler("h: "));
    }

    @Test
    public void simpleCheck() {
        runTestsWithSize(HeaderRemoverHandlerTest::makeInterlacedMessage, () -> IntStream.of(Integer.MAX_VALUE));
    }

    @Test
    public void trivialSingleByte() {
        runTestsWithSize((x,y) -> "A", () -> IntStream.generate(() -> 1));
    }    
    
    @Test
    public void individualBytesCheck() {
        runTestsWithSize(HeaderRemoverHandlerTest::makeThinMessage, () -> IntStream.generate(() -> 1));
        runTestsWithSize(HeaderRemoverHandlerTest::makeInterlacedMessage, () -> IntStream.generate(() -> 1));
        runTestsWithSize(HeaderRemoverHandlerTest::makeConsecutiveMessage, () -> IntStream.generate(() -> 1));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "8,22,22,22,22,9999"
    })
    public void fragmentedCheckInterlaced(String sizesStr) {
        runTestsWithSize(HeaderRemoverHandlerTest::makeInterlacedMessage,
            () -> Arrays.stream(sizesStr.split(",")).mapToInt(Integer::parseInt));
    }

    @Test
    @WrapWithNettyLeakDetection(repetitions = 1)
    public void randomFragmentedCheckInterlaced() {
        final var bound = getBound(HeaderRemoverHandlerTest::makeInterlacedMessage);
        for (int i=0; i<NUM_RANDOM_RUNS; ++i) {
            Random r = new Random(i);
            log.atDebug().setMessage("random run={}").addArgument(i).log();
            runTestsWithSize(HeaderRemoverHandlerTest::makeInterlacedMessage,
                () -> IntStream.generate(() -> r.nextInt(bound)));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "8,22,22,22,22,9999"
    })
    public void fragmentedCheckConsecutive(String sizesStr) {
        runTestsWithSize(HeaderRemoverHandlerTest::makeConsecutiveMessage,
            () -> Arrays.stream(sizesStr.split(",")).mapToInt(Integer::parseInt));
    }

    @Test
    @WrapWithNettyLeakDetection(repetitions = 1)
    public void randomFragmentedCheckConsecutive() {
        final var bound = getBound(HeaderRemoverHandlerTest::makeConsecutiveMessage);
        for (int i=0; i<NUM_RANDOM_RUNS; ++i) {
            Random r = new Random(i);
            log.atDebug().setMessage("random run={}").addArgument(i).log();
            runTestsWithSize(HeaderRemoverHandlerTest::makeConsecutiveMessage,
                () -> IntStream.generate(() -> r.nextInt(bound)));
        }
    }

    private int getBound(BiFunction<Boolean,String,String> makeInterlacedMessage) {
        return Arrays.stream(makeInterlacedMessage.apply(true, "\n").split("\n"))
            .mapToInt(String::length)
            .map(x->x*2)
            .max()
            .orElseThrow(() -> new IllegalStateException("No lines in the sample"));
    }

    static String makeInterlacedMessage(boolean withHosts, String lineEnding) {
        return "GET / HTTP/1.1" + lineEnding +
            "hoststays: v1" + lineEnding +
            (withHosts ? ("HOST: begone" + lineEnding) : "") +
            "different: v2" + lineEnding +
            (withHosts ? ("HosT: begone" + lineEnding) : "") +
            "keeper: v3" + lineEnding +
            lineEnding;
    }

    static String makeThinMessage(boolean withHosts, String lineEnding) {
        return "G" + lineEnding +
            "h: a" + lineEnding +
            (withHosts ? ("HOST: b" + lineEnding) : "") +
            "d: c" + lineEnding +
            (withHosts ? ("HosT: e" + lineEnding) : "") +
            lineEnding;
    }


    static String makeConsecutiveMessage(boolean withHosts, String lineEnding) {
        return "GET / HTTP/1.1" + lineEnding +
            "hoststays: a1" + lineEnding +
            "different: b2" + lineEnding +
            (withHosts ? ("HOST: strike" + lineEnding) : "") +
            (withHosts ? ("HosT: begone" + lineEnding) : "") +
            "e2: c3" + lineEnding +
            "hos: d4" + lineEnding +
            (withHosts ? ("HOST: foo" + lineEnding) : "") +
            (withHosts ? ("HosT: bar" + lineEnding) : "") +
            "X: Y" + lineEnding +
            lineEnding;
    }
}
