package org.opensearch.migrations.replay.datahandlers;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.opensearch.migrations.replay.AggregatedRawResponse;
import org.opensearch.migrations.replay.datatypes.ConnectionReplaySession;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.trafficcapture.netty.UpstreamAlpnProbe;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

/**
 * — selection / caching of the target wire protocol per target URI.
 *
 * <p>Two modes:
 * <ul>
 *   <li><b>H1 mode</b> (default): {@code targetEnableHttp2=false}. Always returns the H1
 *       consumer via the supplied {@link ConsumerFactory}.</li>
 *   <li><b>H2 mode</b>: {@code targetEnableHttp2=true}. Probes the target's ALPN once
 *       per authority and caches the result. When the cached ALPN is {@code "h2"}, returns
 *       per-request consumers minted from a shared {@link H2MultiplexedConsumerFactory}
 *       — so all requests to the same target authority multiplex on a single H2 parent
 *       connection. Otherwise falls back to the H1 consumer.</li>
 * </ul>
 *
 * <p>The {@link #forTarget} factory method wires up the live ALPN probe and lazily creates
 * the multiplex factory — use it for production. The constructors are kept as test seams.
 *
 * <p>Lifecycle: {@link #close()} shuts down all cached multiplex factories. Long-lived
 * (one factory per replayer process is sufficient).
 */
@Slf4j
public class TargetProtocolFactory implements AutoCloseable {

    /** Target ALPN cache: key is target URI authority, value is the negotiated protocol. */
    private final Map<String, String> alpnByAuthority = new ConcurrentHashMap<>();

    /** Per-authority H2 multiplex factory cache. Each value owns one parent H2 connection. */
    private final Map<String, H2MultiplexedConsumerFactory> h2FactoryByAuthority = new ConcurrentHashMap<>();

    /**
     * Per-(authority, sessionId) H2 multiplex factory cache. Each value owns one parent H2
     * connection that is private to one source session. This preserves wire-level fidelity:
     * if the source captured 15 distinct H2 connections, the replayer opens 15 distinct
     * target H2 parents, one per source session.
     *
     * <p>Within a session, multiple requests share the session's parent (true H2 multiplexing
     * — stream sub-channels on the same TCP connection).
     */
    private final Map<String, H2MultiplexedConsumerFactory> h2FactoryBySession = new ConcurrentHashMap<>();

    /** When set, ALPN probing is skipped and this protocol is always returned. Test hook. */
    private final String forcedProtocol;

    /** Whether the replayer was started with H2 target support enabled. */
    private final boolean targetEnableHttp2;

    /** Trust any cert (for self-signed test targets). */
    private final boolean allowInsecure;

    /** Per-request timeout for the H2 consumer. */
    private final Duration h2ConsumerTimeout;

    /** Factory for the H1 consumer; supplied by the caller so we don't pull in heavy deps. */
    private final ConsumerFactory h1Factory;

    @FunctionalInterface
    public interface ConsumerFactory {
        IPacketFinalizingConsumer<AggregatedRawResponse> create(
            ConnectionReplaySession session,
            IReplayContexts.IReplayerHttpTransactionContext ctx,
            Duration timeout);
    }

    public TargetProtocolFactory(boolean targetEnableHttp2, ConsumerFactory h1Factory) {
        this(targetEnableHttp2, h1Factory, null, /*allowInsecure*/ false, Duration.ofSeconds(30));
    }

    public TargetProtocolFactory(boolean targetEnableHttp2,
                                  ConsumerFactory h1Factory,
                                  String forcedProtocol) {
        this(targetEnableHttp2, h1Factory, forcedProtocol, false, Duration.ofSeconds(30));
    }

    public TargetProtocolFactory(boolean targetEnableHttp2,
                                  ConsumerFactory h1Factory,
                                  String forcedProtocol,
                                  boolean allowInsecure,
                                  Duration h2ConsumerTimeout) {
        this.targetEnableHttp2 = targetEnableHttp2;
        this.h1Factory = h1Factory;
        this.forcedProtocol = forcedProtocol;
        this.allowInsecure = allowInsecure;
        this.h2ConsumerTimeout = h2ConsumerTimeout;
    }

    /**
     * Production factory: wires the live ALPN probe so the cached ALPN reflects the actual
     * target's protocol. Falls back to H1 when the target doesn't support h2 OR the probe
     * fails (with a WARN log).
     *
     * <p>Pre-populates the ALPN cache for {@code targetUri} by calling {@link UpstreamAlpnProbe}.
     */
    public static TargetProtocolFactory forTarget(URI targetUri,
                                                   boolean targetEnableHttp2,
                                                   boolean allowInsecure,
                                                   Duration timeout) {
        ConsumerFactory h1Stub = (session, ctx, t) -> null;
        var factory = new TargetProtocolFactory(targetEnableHttp2, h1Stub, null,
                allowInsecure, timeout);
        if (targetEnableHttp2 && "https".equalsIgnoreCase(targetUri.getScheme())) {
            try {
                var negotiated = UpstreamAlpnProbe.probe(targetUri, allowInsecure, timeout);
                var authority = targetUri.getAuthority().toLowerCase(Locale.ROOT);
                factory.alpnByAuthority.put(authority, negotiated == null || negotiated.isEmpty()
                        ? "http/1.1" : negotiated);
                log.atInfo().setMessage("Target {} ALPN probe: negotiated={}")
                    .addArgument(targetUri).addArgument(negotiated).log();
            } catch (Exception e) {
                log.atWarn().setCause(e).setMessage(
                        "Target {} ALPN probe failed; defaulting to http/1.1")
                    .addArgument(targetUri).log();
                factory.alpnByAuthority.put(targetUri.getAuthority().toLowerCase(Locale.ROOT),
                        "http/1.1");
            }
        }
        return factory;
    }

    /**
     * Resolve the per-request consumer.
     *
     * <p>When the cached ALPN for the target is {@code "h2"} AND {@code targetEnableHttp2}
     * is true, returns a per-request consumer minted from the shared
     * {@link H2MultiplexedConsumerFactory} for that target authority — so all H2 requests
     * to the same authority multiplex on a single parent H2 connection.
     *
     * <p>Otherwise falls back to the supplied H1 {@link ConsumerFactory}.
     *
     * <p>NOTE: this overload aggregates ALL requests across ALL source sessions onto a single
     * shared target parent. This is appropriate when source connection identity doesn't matter
     * (e.g., synthetic load tests). For source→target connection-topology fidelity (replay
     * preserves how many distinct H2 connections the source held), call
     * {@link #createForSession} with the session's connectionId instead.
     */
    public IPacketFinalizingConsumer<AggregatedRawResponse> create(
            URI targetUri,
            ConnectionReplaySession session,
            IReplayContexts.IReplayerHttpTransactionContext ctx,
            Duration timeout) {
        var protocol = resolveAlpn(targetUri);
        if (targetEnableHttp2 && "h2".equals(protocol)) {
            // Prefer per-session dispatch when we have a session identity; falls back to
            // per-authority sharing only when the caller has no session context.
            if (session != null) {
                var sid = sessionIdentityFor(session);
                return getOrCreateH2FactoryForSession(targetUri, sid).createConsumer();
            }
            var muxFactory = getOrCreateH2Factory(targetUri);
            log.atDebug().setMessage(
                    "Dispatching to multiplexed H2 consumer for target={} (parent connection shared across all sessions)")
                .addArgument(targetUri).log();
            return muxFactory.createConsumer();
        }
        return h1Factory.create(session, ctx, timeout);
    }

    /**
     * Per-session dispatch: each unique {@code sessionId} gets its own H2 parent connection.
     * Within a session, requests multiplex on the session's parent. Across sessions,
     * connections are kept separate.
     *
     * <p>Use this overload when source→target connection-topology fidelity matters: 15 source
     * sessions map to 15 distinct target H2 parents, each carrying its session's request set.
     */
    public IPacketFinalizingConsumer<AggregatedRawResponse> createForSession(
            URI targetUri,
            String sessionId,
            ConnectionReplaySession session,
            IReplayContexts.IReplayerHttpTransactionContext ctx,
            Duration timeout) {
        var protocol = resolveAlpn(targetUri);
        if (targetEnableHttp2 && "h2".equals(protocol)) {
            return getOrCreateH2FactoryForSession(targetUri, sessionId).createConsumer();
        }
        return h1Factory.create(session, ctx, timeout);
    }

    private H2MultiplexedConsumerFactory getOrCreateH2FactoryForSession(URI targetUri, String sessionId) {
        var authority = targetUri.getAuthority().toLowerCase(Locale.ROOT);
        var key = authority + "|" + sessionId;
        return h2FactoryBySession.computeIfAbsent(key, k -> {
            var f = new H2MultiplexedConsumerFactory(targetUri, allowInsecure, h2ConsumerTimeout);
            try {
                f.open();
                log.atDebug().setMessage("Opened per-session H2 parent for {} session={}")
                    .addArgument(targetUri).addArgument(sessionId).log();
            } catch (Exception e) {
                log.atError().setCause(e).setMessage(
                        "Failed to open per-session H2 parent for {} session={}")
                    .addArgument(targetUri).addArgument(sessionId).log();
                throw new RuntimeException(e);
            }
            return f;
        });
    }

    /** Close the multiplex factory for one specific session. Call when the session ends so
     * the per-session parent connection is released promptly rather than waiting for {@link #close()}. */
    public void closeSession(URI targetUri, String sessionId) {
        var authority = targetUri.getAuthority().toLowerCase(Locale.ROOT);
        var key = authority + "|" + sessionId;
        var removed = h2FactoryBySession.remove(key);
        if (removed != null) {
            try { removed.close(); } catch (Exception ignored) {}
        }
    }

    /** Derive a stable session identity from a ConnectionReplaySession's nodeId+connectionId+generation. */
    private static String sessionIdentityFor(ConnectionReplaySession session) {
        var key = session.getChannelKeyContext().getChannelKey();
        return key.getNodeId() + "|" + key.getConnectionId() + "|" + session.generation;
    }

    private H2MultiplexedConsumerFactory getOrCreateH2Factory(URI targetUri) {
        var authority = targetUri.getAuthority().toLowerCase(Locale.ROOT);
        return h2FactoryByAuthority.computeIfAbsent(authority, a -> {
            var f = new H2MultiplexedConsumerFactory(targetUri, allowInsecure, h2ConsumerTimeout);
            try {
                f.open();
            } catch (Exception e) {
                log.atError().setCause(e).setMessage(
                        "Failed to open H2 multiplex parent for {}; future calls to this authority will fail")
                    .addArgument(targetUri).log();
                throw new RuntimeException(e);
            }
            return f;
        });
    }

    /**
     * Cache or probe the target's ALPN. Returns {@code forcedProtocol} when set,
     * else the cached value (populated by {@link #forTarget} or {@link #setCachedAlpnForTesting}),
     * else falls back to {@code "http/1.1"}.
     */
    private String resolveAlpn(URI targetUri) {
        if (forcedProtocol != null) return forcedProtocol;
        if (!targetEnableHttp2) return "http/1.1";
        var authority = targetUri.getAuthority() == null ? targetUri.toString()
                : targetUri.getAuthority().toLowerCase(Locale.ROOT);
        return alpnByAuthority.computeIfAbsent(authority, this::probeAlpn);
    }

    /** Test seam — overridden by tests; production wiring uses {@link #forTarget}. */
    protected String probeAlpn(@NonNull String authority) {
        return "http/1.1";
    }

    /** Force-set the cached protocol for a target authority. Useful for testing. */
    public void setCachedAlpnForTesting(String authority, String protocol) {
        alpnByAuthority.put(authority.toLowerCase(Locale.ROOT), protocol);
    }

    @Override
    public void close() {
        for (var f : h2FactoryByAuthority.values()) {
            try { f.close(); } catch (Exception ignored) {}
        }
        h2FactoryByAuthority.clear();
        for (var f : h2FactoryBySession.values()) {
            try { f.close(); } catch (Exception ignored) {}
        }
        h2FactoryBySession.clear();
    }
}
