package org.opensearch.migrations.replay.bugfixes;

import java.lang.reflect.Field;

import org.opensearch.migrations.replay.ClientConnectionPool;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.utils.TextTrackedFuture;

import com.google.common.cache.LoadingCache;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Bug 1: ClientConnectionPool.closeConnection() uses the wrong key type for cache invalidation.
 *
 * The cache is keyed by Key(connectionId, sessionNumber), but invalidate() is called with the
 * raw String connectionId. Since String.equals(Key) is always false, the entry is never evicted.
 *
 * This test asserts on the CURRENT BUGGY behavior (cache entry remains after close).
 * When the bug is fixed, this test should FAIL — update the assertion to expect size == 0.
 */
@Slf4j
public class ClientConnectionPoolCacheInvalidationBugTest extends InstrumentationTest {

    @SneakyThrows
    private LoadingCache<?, ?> getCache(ClientConnectionPool pool) {
        Field f = ClientConnectionPool.class.getDeclaredField("connectionId2ChannelCache");
        f.setAccessible(true);
        return (LoadingCache<?, ?>) f.get(pool);
    }

    @Test
    @SneakyThrows
    void closeConnection_doesNotEvictCacheEntry_becauseInvalidateUsesWrongKeyType() {
        // Dummy channel creator — never actually called since we don't send requests
        var pool = new ClientConnectionPool(
            (eventLoop, ctx) -> TextTrackedFuture.completedFuture(null, () -> "dummy"),
            "test-pool",
            1
        );

        try {
            var reqCtx = rootContext.getTestConnectionRequestContext("conn-A", 0);
            var channelKeyCtx = reqCtx.getChannelKeyContext();

            // Put an entry in the cache
            pool.getCachedSession(channelKeyCtx, 0);
            Assertions.assertEquals(1, getCache(pool).size(), "cache should have 1 entry after getCachedSession");

            // Close the connection — this SHOULD evict the cache entry but doesn't due to the bug
            pool.closeConnection(channelKeyCtx, 0);

            // BUG ASSERTION: cache entry is NOT evicted because invalidate(String) != invalidate(Key)
            // When the bug is fixed, this should be 0
            Assertions.assertEquals(1, getCache(pool).size(),
                "BUG: cache entry should have been evicted but wasn't because invalidate() uses String instead of Key");
        } finally {
            pool.shutdownNow().get();
        }
    }
}
