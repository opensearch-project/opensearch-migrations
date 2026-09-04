package org.opensearch.migrations.replay;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;

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
     * Verifies that scheduleClose() keeps the cache entry alive until the close actually runs,
     * so that in-flight response futures can complete on the same session.
     * Immediate invalidation caused deadlocks: new requests got a new session, leaving
     * finishedAccumulatingResponseFuture on the old session permanently incomplete.
     */
    @Test
    @SneakyThrows
    void scheduleClose_cacheRemainsUntilCloseCompletes() throws Exception {        var pool = new ClientConnectionPool(
            (eventLoop, ctx) -> TextTrackedFuture.completedFuture(null, () -> "no channel"),
            "test-pool",
            1
        );
        var orchestrator = new RequestSenderOrchestrator(
            pool,
            (session, ctx) -> null,
            RequestSenderOrchestrator.noSourceTerminationObligations()
        );

        try {
            var channelKeyCtx = rootContext.getTestConnectionRequestContext("conn-A", 0)
                .getChannelKeyContext();

            pool.getCachedSession(channelKeyCtx, 0);
            Assertions.assertEquals(1, getCache(pool).size());

            var closeFuture = orchestrator.scheduleClose(channelKeyCtx, 0, 0, Instant.now());
            closeFuture.get(Duration.ofSeconds(5));

            // After close completes, cache must be evicted
            Assertions.assertEquals(0, getCache(pool).size(),
                "cache must be evicted after close completes");
        } finally {
            pool.shutdownNow().get();
        }
    }

    @Test
    @SneakyThrows
    void differentGenerationsUseDifferentSessionsWithoutCancellingEither() throws Exception {
        var pool = new ClientConnectionPool(
            (eventLoop, ctx) -> TextTrackedFuture.completedFuture(null, () -> "no channel"),
            "test-pool", 1
        );
        var orchestrator = new RequestSenderOrchestrator(
            pool,
            (session, ctx) -> null,
            RequestSenderOrchestrator.noSourceTerminationObligations()
        );

        try {
            var channelKeyCtx = rootContext.getTestConnectionRequestContext("conn-A", 0).getChannelKeyContext();

            // Establish a session at generation 1
            var session1 = pool.getCachedSession(channelKeyCtx, 0, 1);
            Assertions.assertEquals(1, session1.generation);

            var session2 = pool.getCachedSession(channelKeyCtx, 0, 2);
            Assertions.assertNotSame(session1, session2);
            Assertions.assertEquals(2, session2.generation);
            Assertions.assertFalse(session1.isCancelled());
            Assertions.assertFalse(session2.isCancelled());
            Assertions.assertEquals(2, getCache(pool).size());
        } finally {
            pool.shutdownNow().get();
        }
    }

    /**
     * Verifies that the generation from ITrafficStreamKey flows through scheduleRequest
     * to getCachedSession, so new sessions are created with the correct generation.
     * Session cancellation on generation bump is NOT done here (would cause deadlocks);
     * it is handled by the synthetic close path.
     */
    @Test
    @SneakyThrows
    void scheduleRequest_generationFlowsThroughToSessionLookup() throws Exception {
        var pool = new ClientConnectionPool(
            (eventLoop, ctx) -> TextTrackedFuture.completedFuture(null, () -> "no channel"),
            "test-pool", 1
        );

        try {
            var channelKeyCtx = rootContext.getTestConnectionRequestContext("conn-A", 0).getChannelKeyContext();

            // First session created with generation 1
            var session1 = pool.getCachedSession(channelKeyCtx, 0, 1);
            Assertions.assertEquals(1, session1.generation);

            // After the synthetic close path invalidates the cache, a new session
            // created via getCachedSession with generation 2 carries generation 2
            pool.invalidateSession(channelKeyCtx.getConnectionId(), 0);
            var session2 = pool.getCachedSession(channelKeyCtx, 0, 2);
            Assertions.assertEquals(2, session2.generation,
                "new session created after cache invalidation must carry the new generation");
            Assertions.assertNotSame(session1, session2);
        } finally {
            pool.shutdownNow().get();
        }
    }

    @Test
    @SneakyThrows
    void oldGenerationInvalidationCannotEvictNewGeneration() throws Exception {
        var pool = new ClientConnectionPool(
            (eventLoop, ctx) -> TextTrackedFuture.completedFuture(null, () -> "no channel"),
            "test-pool", 1
        );
        try {
            var channelKeyCtx = rootContext.getTestConnectionRequestContext("conn-A", 0).getChannelKeyContext();

            var session1 = pool.getCachedSession(channelKeyCtx, 0, 1);
            Assertions.assertEquals(1, session1.generation, "session must carry the generation it was created with");

            var session2 = pool.getCachedSession(channelKeyCtx, 0, 2);
            pool.invalidateSession(channelKeyCtx.getConnectionId(), 0, 1);

            Assertions.assertEquals(1, getCache(pool).size());
            Assertions.assertSame(session2, pool.getCachedSession(channelKeyCtx, 0, 2));
            Assertions.assertNotSame(session1, session2);
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
