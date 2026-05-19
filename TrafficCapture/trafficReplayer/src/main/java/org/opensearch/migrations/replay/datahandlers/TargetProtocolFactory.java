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
     * to the same authority share a single parent H2 connection.
     *
     * <p>Otherwise falls back to the supplied H1 {@link ConsumerFactory}.
     */
    public IPacketFinalizingConsumer<AggregatedRawResponse> create(
            URI targetUri,
            ConnectionReplaySession session,
            IReplayContexts.IReplayerHttpTransactionContext ctx,
            Duration timeout) {
        var protocol = resolveAlpn(targetUri);
        if (targetEnableHttp2 && "h2".equals(protocol)) {
            var muxFactory = getOrCreateH2Factory(targetUri);
            log.atDebug().setMessage(
                    "Dispatching to multiplexed H2 consumer for target={} (parent connection shared)")
                .addArgument(targetUri).log();
            return muxFactory.createConsumer();
        }
        return h1Factory.create(session, ctx, timeout);
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
    }
}
