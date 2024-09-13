package org.opensearch.migrations.trafficcapture.proxyserver.netty;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Pattern;

import com.google.common.base.Strings;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@Slf4j
public class MatcherTest {

    public static final ByteBuf BIG_BUF =
        Unpooled.wrappedBuffer(Strings.repeat("ha", 100_000).getBytes(StandardCharsets.UTF_8));
    public static final ByteBuf SMALL_BUF =
        Unpooled.wrappedBuffer(Strings.repeat("ha", 1).getBytes(StandardCharsets.UTF_8));

    @Test
    public void test() {
        var p = Pattern.compile("^host:.*", Pattern.CASE_INSENSITIVE);

        Assertions.assertTrue(
            bufMatches(p, Unpooled.wrappedBuffer("host: MYHOST".getBytes(StandardCharsets.UTF_8))));

        getMatchTime(p, BIG_BUF, 1000);
        getMatchTime(p, BIG_BUF, 1000);

        for (int i=0; i<1; ++i) {
            final var MATCH_REPS = 100_000_000;
            var smallTime = getMatchTime(p, SMALL_BUF, MATCH_REPS);
            var bigTime = getMatchTime(p, BIG_BUF, MATCH_REPS);
            log.info("smallTime = "+smallTime);
            log.info("bigTime   = "+bigTime);
        }
    }

    private static Duration getMatchTime(Pattern p, ByteBuf input, int i) {
        final var start = System.nanoTime();
        boolean didMatch = false;
        for (; i > 0; --i) {
            didMatch |= bufMatches(p, input);
        }
        try {
            return Duration.ofNanos(System.nanoTime() - start);
        } finally {
            Assertions.assertFalse(didMatch);
        }
    }

    public static boolean bufMatches(Pattern p, ByteBuf b) {
        return p.matcher(b.getCharSequence(0, b.readableBytes(),StandardCharsets.UTF_8)).matches();
    }
}
