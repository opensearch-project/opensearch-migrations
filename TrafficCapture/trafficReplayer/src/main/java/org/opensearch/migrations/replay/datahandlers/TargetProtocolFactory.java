package org.opensearch.migrations.replay.datahandlers;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.opensearch.migrations.replay.AggregatedRawResponse;
import org.opensearch.migrations.replay.datatypes.ConnectionReplaySession;
import org.opensearch.migrations.replay.tracing.IReplayContexts;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

/**
 * RFC 0001 §8.5 — selection / caching of the target wire protocol per target URI.
 *
 * <p>Today this class always returns a {@link NettyPacketToHttpConsumer} (H1 only) because
 * the H2 target-side consumer ({@code H2NettyPacketToHttpConsumer}) hasn't landed yet.
 * Once that class exists, {@link #create} dispatches based on the cached negotiated ALPN
 * for the target URI.
 *
 * <p>The factory contract is intentionally narrow so that callers ({@code TrafficReplayerCore},
 * the replay engine) don't need to know whether the target speaks H1 or H2. A future
 * commit replaces the body of {@link #create} with a probe-and-cache that opens one ALPN
 * handshake per target URI, then returns the corresponding consumer type for every
 * subsequent request.
 */
@Slf4j
public class TargetProtocolFactory {

    /** Target ALPN cache: key is target URI authority, value is the negotiated protocol. */
    private final Map<String, String> alpnByAuthority = new ConcurrentHashMap<>();

    /** When set, ALPN probing is skipped and this protocol is always returned. Test hook. */
    private final String forcedProtocol;

    /** Whether the replayer was started with H2 target support enabled. */
    private final boolean targetEnableHttp2;

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
        this(targetEnableHttp2, h1Factory, null);
    }

    public TargetProtocolFactory(boolean targetEnableHttp2,
                                  ConsumerFactory h1Factory,
                                  String forcedProtocol) {
        this.targetEnableHttp2 = targetEnableHttp2;
        this.h1Factory = h1Factory;
        this.forcedProtocol = forcedProtocol;
    }

    /**
     * Resolve the per-request consumer. Returns the H1 consumer today; future commit will
     * dispatch to the H2 consumer when {@code targetEnableHttp2} is set AND the cached ALPN
     * for this target URI is {@code "h2"}.
     */
    public IPacketFinalizingConsumer<AggregatedRawResponse> create(
            URI targetUri,
            ConnectionReplaySession session,
            IReplayContexts.IReplayerHttpTransactionContext ctx,
            Duration timeout) {
        var protocol = resolveAlpn(targetUri);
        if ("h2".equals(protocol) && targetEnableHttp2) {
            // T6.2 lands the real H2 consumer; until then fall through to H1 with a debug log.
            log.atDebug().setMessage(
                    "Target {} negotiated h2 but H2NettyPacketToHttpConsumer is not yet implemented. "
                        + "Falling through to H1 consumer.").addArgument(targetUri).log();
        }
        return h1Factory.create(session, ctx, timeout);
    }

    /**
     * Cache or probe the target's ALPN. Today this returns {@code forcedProtocol} when set,
     * else {@code "http/1.1"} (no real probe yet). T6.1's full implementation opens one
     * handshake per target authority and caches the result.
     */
    private String resolveAlpn(URI targetUri) {
        if (forcedProtocol != null) return forcedProtocol;
        if (!targetEnableHttp2) return "http/1.1";
        var authority = targetUri.getAuthority() == null ? targetUri.toString()
                : targetUri.getAuthority().toLowerCase(Locale.ROOT);
        return alpnByAuthority.computeIfAbsent(authority, this::probeAlpn);
    }

    /**
     * One-shot ALPN probe of the target. Default implementation is a stub that returns
     * {@code "http/1.1"} so the existing replayer keeps working. A future commit replaces
     * this with a real Netty handshake.
     */
    protected String probeAlpn(@NonNull String authority) {
        return "http/1.1";
    }

    /** Force-set the cached protocol for a target authority. Useful for testing. */
    public void setCachedAlpnForTesting(String authority, String protocol) {
        alpnByAuthority.put(authority.toLowerCase(Locale.ROOT), protocol);
    }
}
