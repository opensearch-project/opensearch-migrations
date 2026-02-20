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
 * Verifies that ClientConnectionPool.closeConnection() uses the correct composite Key for cache invalidation.
 *
 * The cache is keyed by Key(connectionId, sessionNumber). If invalidate() were called with the
 * raw String connectionId, String.equals(Key) would always be false and the entry would never be evicted.
 *
 * This test verifies that the cache entry is properly evicted after closeConnection().
 */
@Slf4j
public class ClientConnectionPoolCacheInvalidationTest extends InstrumentationTest {

    @SneakyThrows
    private LoadingCache<?, ?> getCache(ClientConnectionPool pool) {
        Field f = ClientConnectionPool.class.getDeclaredField("connectionId2ChannelCache");
        f.setAccessible(true);
        return (LoadingCache<?, ?>) f.get(pool);
    }

    @Test
    @SneakyThrows
    void closeConnection_evictsCacheEntry() {
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

            // Close the connection — this should evict the cache entry
            pool.closeConnection(channelKeyCtx, 0);

            // Cache entry is properly evicted because invalidate() uses the correct Key type
            Assertions.assertEquals(0, getCache(pool).size(),
                "cache entry should be evicted after closeConnection()");
        } finally {
            pool.shutdownNow().get();
        }
    }
}
