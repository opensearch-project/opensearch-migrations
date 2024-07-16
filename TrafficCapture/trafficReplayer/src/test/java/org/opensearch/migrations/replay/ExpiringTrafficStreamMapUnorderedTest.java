package org.opensearch.migrations.replay;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamKeyAndContext;
import org.opensearch.migrations.replay.traffic.expiration.BehavioralPolicy;
import org.opensearch.migrations.replay.traffic.expiration.ExpiringTrafficStreamMap;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;
import org.opensearch.migrations.tracing.InstrumentationTest;

@WrapWithNettyLeakDetection(disableLeakChecks = true)
class ExpiringTrafficStreamMapUnorderedTest extends InstrumentationTest {

    public static final String TEST_NODE_ID_STRING = "test_node_id";

    public void testExpirations(
        Function<Integer, String> connectionGenerator,
        int window,
        int granularity,
        int timestamps[],
        int expectedExpirationCounts[]
    ) {
        var expiredAccumulations = new ArrayList<Accumulation>();
        var expiringMap = new ExpiringTrafficStreamMap(
            Duration.ofSeconds(window),
            Duration.ofSeconds(granularity),
            new BehavioralPolicy() {
                @Override
                public void onExpireAccumulation(String partitionId, Accumulation accumulation) {
                    expiredAccumulations.add(accumulation);
                }
            }
        );
        var createdAccumulations = new ArrayList<Accumulation>();
        var expiredCountsPerLoop = new ArrayList<Integer>();
        for (int i = 0; i < expectedExpirationCounts.length; ++i) {
            var ts = Instant.ofEpochSecond(timestamps[i]);
            var tsk = PojoTrafficStreamKeyAndContext.build(
                TEST_NODE_ID_STRING,
                connectionGenerator.apply(i),
                0,
                rootContext::createTrafficStreamContextForTest
            );
            var accumulation = expiringMap.getOrCreateWithoutExpiration(tsk, k -> new Accumulation(tsk, 0));
            expiringMap.expireOldEntries(
                PojoTrafficStreamKeyAndContext.build(
                    TEST_NODE_ID_STRING,
                    connectionGenerator.apply(i),
                    0,
                    rootContext::createTrafficStreamContextForTest
                ),
                accumulation,
                ts
            );
            createdAccumulations.add(accumulation);
            if (accumulation != null) {
                var rrPair = accumulation.getOrCreateTransactionPair(
                    PojoTrafficStreamKeyAndContext.build("n", "c", 1, rootContext::createTrafficStreamContextForTest),
                    Instant.EPOCH
                );
                rrPair.addResponseData(ts, ("Add" + i).getBytes(StandardCharsets.UTF_8));
            }
            expiredCountsPerLoop.add(expiredAccumulations.size());
        }
        Assertions.assertEquals(
            Arrays.stream(expectedExpirationCounts).mapToObj(i -> "" + i).collect(Collectors.joining()),
            expiredCountsPerLoop.stream().map(i -> "" + i).collect(Collectors.joining())
        );
    }

    @Test
    public void testConnectionsAreExpired1() {
        testExpirations(
            i -> "connectionId_" + i,
            5,
            1,
            new int[] { 1, 6, 3, 8, 4, 4, 3, 9, 12 },
            new int[] { 0, 0, 0, 1, 1, 1, 1, 1, 3 }
        );
    }

    @Test
    public void testConnectionsAreExpired2() {
        testExpirations(
            i -> "connectionId_" + i,
            5,
            1,
            new int[] { 1, 7, 3, 8, 4, 4, 3, 9, 13, 15 },
            new int[] { 0, 1, 1, 1, 1, 1, 1, 1, 3, 8 }
        );
    }
}
