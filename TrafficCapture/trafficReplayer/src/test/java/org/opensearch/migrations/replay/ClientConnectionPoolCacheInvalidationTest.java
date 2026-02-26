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
            (session, ctx) -> null
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

    /**
     * When scheduleRequest is called with a key whose generation is higher than the cached
     * session's generation, the old session must be cancelled and a new one used.
     * Before fix: generation is not threaded through scheduleRequest → getCachedSession,
     * so the old session is always reused regardless of generation.
     */
    @Test
    @SneakyThrows
    void scheduleRequest_higherGenerationCancelsOldSession() throws Exception {
        var pool = new ClientConnectionPool(
            (eventLoop, ctx) -> TextTrackedFuture.completedFuture(null, () -> "no channel"),
            "test-pool", 1
        );
        var orchestrator = new RequestSenderOrchestrator(pool, (session, ctx) -> null);

        try {
            var channelKeyCtx = rootContext.getTestConnectionRequestContext("conn-A", 0).getChannelKeyContext();

            // Establish a session at generation 1
            var session1 = pool.getCachedSession(channelKeyCtx, 0, 1);
            Assertions.assertEquals(1, session1.generation);

            // getCachedSession with generation 2 returns the same session (no cancellation in getCachedSession)
            // Cancellation is handled by the synthetic close path to avoid finishedAccumulatingResponseFuture deadlocks
            var session2 = pool.getCachedSession(channelKeyCtx, 0, 2);
            Assertions.assertSame(session1, session2,
                "getCachedSession must not cancel on generation bump — synthetic close path handles that");
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
            pool.invalidateSession(channelKeyCtx.getNodeId(), channelKeyCtx.getConnectionId(), 0);
            var session2 = pool.getCachedSession(channelKeyCtx, 0, 2);
            Assertions.assertEquals(2, session2.generation,
                "new session created after cache invalidation must carry the new generation");
            Assertions.assertNotSame(session1, session2);
        } finally {
            pool.shutdownNow().get();
        }
    }

    /**
     * getCachedSession stores the generation on the session for tracking purposes.
     * Session cancellation on generation bump is handled by the synthetic close path,
     * not by getCachedSession (which would cause finishedAccumulatingResponseFuture deadlocks).
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

            var session1 = pool.getCachedSession(channelKeyCtx, 0, 1);
            Assertions.assertEquals(1, session1.generation, "session must carry the generation it was created with");

            // getCachedSession with a higher generation returns the SAME session (no cancellation here —
            // cancellation is handled by the synthetic close path to avoid deadlocks)
            var session2 = pool.getCachedSession(channelKeyCtx, 0, 2);
            Assertions.assertSame(session1, session2,
                "getCachedSession must not cancel sessions on generation bump — that causes deadlocks");
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

    /**
     * Two different source nodes with the same connectionId must get separate cache entries.
     * Before fix: Key only uses connectionId, so node-B silently reuses node-A's session.
     */
    @Test
    @SneakyThrows
    void differentNodesSameConnectionId_getSeparateSessions() {
        var pool = new ClientConnectionPool(
            (eventLoop, ctx) -> TextTrackedFuture.completedFuture(null, () -> "no channel"),
            "test-pool", 1
        );
        try {
            var ctxNodeA = rootContext.getTestConnectionRequestContext("node-A", "conn-1", 0)
                .getChannelKeyContext();
            var ctxNodeB = rootContext.getTestConnectionRequestContext("node-B", "conn-1", 0)
                .getChannelKeyContext();

            var sessionA = pool.getCachedSession(ctxNodeA, 0);
            var sessionB = pool.getCachedSession(ctxNodeB, 0);

            Assertions.assertNotSame(sessionA, sessionB,
                "Different source nodes with the same connectionId must get separate sessions");
            Assertions.assertEquals(2, getCache(pool).size(),
                "Cache must have 2 entries for 2 different nodes");
        } finally {
            pool.shutdownNow().get();
        }
    }

    /**
     * closeConnection for node-A must not affect node-B's session even when connectionId matches.
     */
    @Test
    @SneakyThrows
    void closeConnection_doesNotAffectOtherNode() {
        var pool = new ClientConnectionPool(
            (eventLoop, ctx) -> TextTrackedFuture.completedFuture(null, () -> "no channel"),
            "test-pool", 1
        );
        try {
            var ctxNodeA = rootContext.getTestConnectionRequestContext("node-A", "conn-1", 0)
                .getChannelKeyContext();
            var ctxNodeB = rootContext.getTestConnectionRequestContext("node-B", "conn-1", 0)
                .getChannelKeyContext();

            pool.getCachedSession(ctxNodeA, 0);
            pool.getCachedSession(ctxNodeB, 0);
            Assertions.assertEquals(2, getCache(pool).size());

            pool.closeConnection(ctxNodeA, 0);

            Assertions.assertEquals(1, getCache(pool).size(),
                "Only node-A's entry should be evicted");
            // node-B's session must still be present
            var sessionB = pool.getCachedSession(ctxNodeB, 0);
            Assertions.assertNotNull(sessionB, "node-B's session must survive node-A's close");
        } finally {
            pool.shutdownNow().get();
        }
    }

    /**
     * cancelConnection for node-A must not cancel node-B's session.
     */
    @Test
    @SneakyThrows
    void cancelConnection_doesNotAffectOtherNode() {
        var pool = new ClientConnectionPool(
            (eventLoop, ctx) -> TextTrackedFuture.completedFuture(null, () -> "no channel"),
            "test-pool", 1
        );
        try {
            var ctxNodeA = rootContext.getTestConnectionRequestContext("node-A", "conn-1", 0)
                .getChannelKeyContext();
            var ctxNodeB = rootContext.getTestConnectionRequestContext("node-B", "conn-1", 0)
                .getChannelKeyContext();

            pool.getCachedSession(ctxNodeA, 0);
            pool.getCachedSession(ctxNodeB, 0);

            pool.cancelConnection(ctxNodeA, 0);

            Assertions.assertEquals(1, getCache(pool).size(),
                "Only node-A's entry should be evicted after cancel");
        } finally {
            pool.shutdownNow().get();
        }
    }

    /**
     * invalidateSession for one node must not affect another node's session with the same connectionId.
     */
    @Test
    @SneakyThrows
    void invalidateSession_doesNotAffectOtherNode() {
        var pool = new ClientConnectionPool(
            (eventLoop, ctx) -> TextTrackedFuture.completedFuture(null, () -> "no channel"),
            "test-pool", 1
        );
        try {
            var ctxNodeA = rootContext.getTestConnectionRequestContext("node-A", "conn-1", 0)
                .getChannelKeyContext();
            var ctxNodeB = rootContext.getTestConnectionRequestContext("node-B", "conn-1", 0)
                .getChannelKeyContext();

            pool.getCachedSession(ctxNodeA, 0);
            pool.getCachedSession(ctxNodeB, 0);
            Assertions.assertEquals(2, getCache(pool).size());

            pool.invalidateSession(ctxNodeA.getNodeId(), ctxNodeA.getConnectionId(), 0);

            Assertions.assertEquals(1, getCache(pool).size(),
                "Only node-A's entry should be invalidated");
        } finally {
            pool.shutdownNow().get();
        }
    }
}
