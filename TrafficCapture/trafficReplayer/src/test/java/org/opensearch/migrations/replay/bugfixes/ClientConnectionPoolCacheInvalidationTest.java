package org.opensearch.migrations.replay.bugfixes;

import java.lang.reflect.Field;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import org.opensearch.migrations.replay.ClientConnectionPool;
import org.opensearch.migrations.replay.RequestSenderOrchestrator;
import org.opensearch.migrations.replay.TrafficReplayerTopLevel;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.utils.TextTrackedFuture;

import com.google.common.cache.LoadingCache;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Verifies that ClientConnectionPool.closeConnection() uses the correct composite Key for cache invalidation,
 * and that scheduleClose() invalidates the cache immediately (before the Netty close completes).
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

    /**
     * Verifies that scheduleClose() invalidates the cache entry immediately — before the Netty
     * channel close completes — so that new requests arriving after scheduleClose() get a fresh
     * ConnectionReplaySession rather than the one being closed.
     *
     * Before the fix: the cache is only invalidated inside the Netty close completion callback,
     * leaving a window where new requests reuse the closing session.
     * After the fix: the cache is invalidated synchronously in scheduleClose().
     */
    @Test
    @SneakyThrows
    void scheduleClose_invalidatesCacheImmediately() throws Exception {        var pool = new ClientConnectionPool(
            (eventLoop, ctx) -> TextTrackedFuture.completedFuture(null, () -> "no channel"),
            "test-pool",
            1
        );
        var orchestrator = new RequestSenderOrchestrator(
            pool,
            (session, ctx) -> null // no packet consumer needed
        );

        try {
            var channelKeyCtx = rootContext.getTestConnectionRequestContext("conn-A", 0)
                .getChannelKeyContext();

            // Populate the cache
            pool.getCachedSession(channelKeyCtx, 0);
            Assertions.assertEquals(1, getCache(pool).size());

            // Schedule a close — should invalidate the cache immediately
            var closeFuture = orchestrator.scheduleClose(channelKeyCtx, 0, 0, Instant.now());

            // Cache must be empty NOW, before the close future completes
            Assertions.assertEquals(0, getCache(pool).size(),
                "cache must be invalidated immediately when scheduleClose() is called, "
                    + "not deferred until the Netty close completes");

            closeFuture.get(Duration.ofSeconds(5));
        } finally {
            pool.shutdownNow().get();
        }
    }

    // -------------------------------------------------------------------------
    // Phase 5: ConnectionReplaySession generation + cancellation
    // -------------------------------------------------------------------------

    /**
     * When getCachedSession is called with a generation higher than the cached session's
     * generation, the old session must be cancelled and a new one returned.
     * Before fix: no generation check — same session returned regardless of generation.
     */
    @Test
    @SneakyThrows
    void higherGenerationCancelsOldSession() throws Exception {
        var pool = new ClientConnectionPool(
            (eventLoop, ctx) -> TextTrackedFuture.completedFuture(null, () -> "no channel"),
            "test-pool", 1
        );
        try {
            var channelKeyCtx = rootContext.getTestConnectionRequestContext("conn-A", 0).getChannelKeyContext();

            var session1 = pool.getCachedSession(channelKeyCtx, 0, 1); // generation 1
            var session2 = pool.getCachedSession(channelKeyCtx, 0, 2); // generation 2 — should cancel session1

            Assertions.assertNotSame(session1, session2,
                "higher generation must produce a new session, not reuse the old one");
            Assertions.assertEquals(2, session2.generation,
                "new session must carry the new generation");
        } finally {
            pool.shutdownNow().get();
        }
    }

    /**
     * Same generation must reuse the existing session.
     */
    @Test
    @SneakyThrows
    void sameGenerationReusesSession() throws Exception {
        var pool = new ClientConnectionPool(
            (eventLoop, ctx) -> TextTrackedFuture.completedFuture(null, () -> "no channel"),
            "test-pool", 1
        );
        try {
            var channelKeyCtx = rootContext.getTestConnectionRequestContext("conn-A", 0).getChannelKeyContext();
            var session1 = pool.getCachedSession(channelKeyCtx, 0, 1);
            var session2 = pool.getCachedSession(channelKeyCtx, 0, 1);
            Assertions.assertSame(session1, session2, "same generation must reuse the existing session");
        } finally {
            pool.shutdownNow().get();
        }
    }
}
