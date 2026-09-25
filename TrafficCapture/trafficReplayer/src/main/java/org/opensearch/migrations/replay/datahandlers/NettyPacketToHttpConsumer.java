package org.opensearch.migrations.replay.datahandlers;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

import org.opensearch.migrations.replay.AggregatedRawResponse;
import org.opensearch.migrations.replay.datahandlers.http.helpers.ReadMeteringHandler;
import org.opensearch.migrations.replay.datahandlers.http.helpers.WriteMeteringHandler;
import org.opensearch.migrations.replay.datatypes.AttemptPayload;
import org.opensearch.migrations.replay.datatypes.OwnedPreparedRequest;
import org.opensearch.migrations.replay.identity.ConnectionProcessingId;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.TargetAttemptOutcome;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.TargetAttemptOutcome.NoTargetResponseDiagnostic;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.TargetAttemptOutcome.NoTargetResponseKind;
import org.opensearch.migrations.replay.lifecycle.TargetChannelPort;
import org.opensearch.migrations.replay.netty.BacksideHttpWatcherHandler;
import org.opensearch.migrations.replay.netty.BacksideSnifferHandler;
import org.opensearch.migrations.replay.netty.InterimHttpResponseHandler;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.utils.TextTrackedFuture;
import org.opensearch.migrations.utils.TrackedFuture;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoop;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpResponseDecoder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.util.concurrent.ScheduledFuture;
import lombok.NonNull;

// REBUILD-TRACE-START(G5,target): retain through the rebuild; remove in final pre-merge cleanup.
// old constructor -> create + DeployedTargetPacketConsumer constructor
// activateLiveChannel -> DeployedTargetPacketConsumerFactory.acquireChannel/connect
// createClientConnectionFactory/createClientConnection/ClientConnectionAttempt ->
//     create + DeployedTargetPacketConsumerFactory.connect/initializeTls
// createClientConnection eventLoop.isShuttingDown guard ->
//     DeployedTargetPacketConsumerFactory.connect's explicit exceptional guard
// initializeConnectionHandlers -> initializeTls + ConnectionClosedListenerHandler
// channelIsInUse -> DeployedTargetPacketConsumerFactory.channelIsInUse
// initializeRequestHandlers -> DeployedTargetPacketConsumer.initializeRequestHandlers
// consumeBytes -> ActiveAttempt.sendPacket
// sendPacket/writePacketAndUpdateFuture -> DeployedTargetPacketConsumer.sendPacket
// classifyPacketTransportFailure -> settleTransportFailure + ActiveAttempt.classify
// finalizeRequest -> ActiveAttempt.finalizeResponse +
//     DeployedTargetPacketConsumer.finalizeRequest/finishResponse
// deactivateChannel -> DeployedTargetPacketConsumer.resetForReuse/finishResponse
// abort -> ActiveAttempt.abort + DeployedTargetPacketConsumer.abort +
//     DeployedTargetPacketConsumerFactory.close
// setCurrentMessageContext/getCurrentRequestSpan/getParentContext/closeSpans ->
//     transitionTo/closeRequestPhase; request identity comes from AttemptInput.replayContext
// old connect retry timer -> RequestReplayOwner.scheduleRetry, so no permit spans backoff
// OutboundRequestMethod/RequestMethodAwareHttpResponseDecoder -> same-named nested types
// REBUILD-TRACE-END(G5,target)

/**
 * Production Netty implementation of the connection-scoped {@link TargetChannelPort}.
 *
 * <p>Neither {@link TargetPacketConsumer} nor {@link OwnedPreparedRequest} enters
 * connection/request owner state.</p>
 */
public final class NettyPacketToHttpConsumer
    implements TargetChannelPort<
        NettyPacketToHttpConsumer.PreparedRequest,
        AggregatedRawResponse
    > {

    public record PreparedRequest(
        @NonNull OwnedPreparedRequest request,
        @NonNull Duration packetInterval
    ) implements AutoCloseable {
        public PreparedRequest {
            if (packetInterval.isNegative()) {
                throw new IllegalArgumentException("packetInterval must not be negative");
            }
        }

        @Override
        public void close() {
            request.close();
        }
    }

    /**
     * Existing Netty implementations are adapted here, outside the owner contracts.
     *
     * <p>{@code targetWriteAccepted} receives one-based packet ordinals at the exact
     * {@code channel.writeAndFlush} acceptance point.</p>
     */
    public interface TargetPacketConsumerFactory {
        TargetPacketConsumer create(
            TargetChannelPort.AttemptInput<PreparedRequest> input,
            IntConsumer targetWriteAccepted
        );

        CompletionStage<Void> close(ConnectionProcessingId connectionProcessingId);
    }

    /**
     * Builds the production Netty implementation of the G5 target-channel contract.
     */
    // REBUILD-TRACE(G5,target): old constructor + createClientConnectionFactory.
    public static NettyPacketToHttpConsumer create(
        @NonNull ConnectionProcessingId connectionProcessingId,
        @NonNull EventLoop eventLoop,
        @NonNull Clock clock,
        @NonNull IReplayContexts.IConnectionContext connectionContext,
        @NonNull URI serverUri,
        SslContext sslContext,
        @NonNull Duration readTimeout
    ) {
        if (readTimeout.isNegative() || readTimeout.isZero()) {
            throw new IllegalArgumentException("readTimeout must be positive");
        }
        return new NettyPacketToHttpConsumer(
            connectionProcessingId,
            eventLoop,
            clock,
            new DeployedTargetPacketConsumerFactory(
                connectionProcessingId,
                eventLoop,
                clock,
                connectionContext,
                serverUri,
                sslContext,
                readTimeout
            )
        );
    }

    private final ConnectionProcessingId connectionProcessingId;
    private final EventLoop eventLoop;
    private final Clock clock;
    private final TargetPacketConsumerFactory packetConsumerFactory;
    private ActiveAttempt activeAttempt;
    private boolean closed;

    public NettyPacketToHttpConsumer(
        @NonNull ConnectionProcessingId connectionProcessingId,
        @NonNull EventLoop eventLoop,
        @NonNull Clock clock,
        @NonNull TargetPacketConsumerFactory packetConsumerFactory
    ) {
        this.connectionProcessingId = connectionProcessingId;
        this.eventLoop = eventLoop;
        this.clock = clock;
        this.packetConsumerFactory = packetConsumerFactory;
    }

    @Override
    // REBUILD-TRACE(G5,target): old RequestSenderOrchestrator.sendPackets request boundary.
    public Attempt<AggregatedRawResponse> startAttempt(AttemptInput<PreparedRequest> input) {
        requireOwnerThread();
        if (closed) {
            throw new IllegalStateException(
                "target channel is closed for " + connectionProcessingId
            );
        }
        if (!connectionProcessingId.equals(input.connectionProcessingId())) {
            throw new IllegalArgumentException(
                "attempt for " + input.connectionProcessingId()
                    + " cannot use target channel for " + connectionProcessingId
            );
        }
        if (activeAttempt != null) {
            throw new IllegalStateException(
                "another target attempt is active for " + connectionProcessingId
            );
        }

        final AttemptPayload payload;
        try {
            payload = Objects.requireNonNull(
                input.preparedRequest().request().newAttempt(),
                "prepared request returned no attempt payload"
            );
        } catch (Throwable failure) {
            return failedAttempt(failure);
        }

        var attempt = new ActiveAttempt(input, payload);
        activeAttempt = attempt;
        try {
            attempt.start();
        } catch (Throwable failure) {
            attempt.fail(failure);
        }
        return attempt;
    }

    @Override
    // REBUILD-TRACE(G5,target): old ConnectionReplaySession/ClientConnectionPool close boundary.
    public CompletionStage<Void> close(ConnectionProcessingId reportedConnectionId) {
        requireOwnerThread();
        if (!connectionProcessingId.equals(reportedConnectionId)) {
            throw new IllegalArgumentException(
                "close for " + reportedConnectionId
                    + " cannot use target channel for " + connectionProcessingId
            );
        }
        if (activeAttempt != null) {
            throw new IllegalStateException(
                "cannot close target channel with an active attempt for "
                    + connectionProcessingId
            );
        }
        closed = true;
        return Objects.requireNonNull(
            packetConsumerFactory.close(connectionProcessingId),
            "target packet consumer factory returned no close stage"
        );
    }

    private Attempt<AggregatedRawResponse> failedAttempt(Throwable failure) {
        return new Attempt<>() {
            @Override
            public CompletionStage<TargetAttemptOutcome<AggregatedRawResponse>> outcome() {
                return CompletableFuture
                    .<TargetAttemptOutcome<AggregatedRawResponse>>failedFuture(failure)
                    .minimalCompletionStage();
            }

            @Override
            public CompletionStage<Void> abort(CancellationException cause) {
                return CompletableFuture.completedFuture(null);
            }
        };
    }

    private void requireOwnerThread() {
        if (!eventLoop.inEventLoop()) {
            throw new IllegalStateException(
                "target channel access must run on its connection event loop"
            );
        }
    }

    private static final class DeployedTargetPacketConsumerFactory
        implements TargetPacketConsumerFactory {
        private static final Optional<LogLevel> PIPELINE_LOGGING = Optional.empty();
        private static final String CONNECTION_CLOSE_HANDLER = "connectionClose";
        private static final String SSL_HANDLER = "ssl";
        private static final String READ_TIMEOUT_HANDLER = "readTimeout";
        private static final String WRITE_METER_HANDLER = "writeMeter";
        private static final String READ_METER_HANDLER = "readMeter";
        private static final String RESPONSE_WATCHER_HANDLER = "responseWatcher";

        private final ConnectionProcessingId connectionProcessingId;
        private final EventLoop eventLoop;
        private final Clock clock;
        private final IReplayContexts.IConnectionContext connectionContext;
        private final URI serverUri;
        private final SslContext sslContext;
        private final Duration readTimeout;
        private Channel channel;
        private boolean closed;

        private DeployedTargetPacketConsumerFactory(
            ConnectionProcessingId connectionProcessingId,
            EventLoop eventLoop,
            Clock clock,
            IReplayContexts.IConnectionContext connectionContext,
            URI serverUri,
            SslContext sslContext,
            Duration readTimeout
        ) {
            this.connectionProcessingId = connectionProcessingId;
            this.eventLoop = eventLoop;
            this.clock = clock;
            this.connectionContext = connectionContext;
            this.serverUri = serverUri;
            this.sslContext = sslContext;
            this.readTimeout = readTimeout;
        }

        @Override
        // REBUILD-TRACE(G5,target): old request-scoped constructor.
        public TargetPacketConsumer create(
            TargetChannelPort.AttemptInput<PreparedRequest> input,
            IntConsumer targetWriteAccepted
        ) {
            requireOwnerThread();
            if (closed) {
                throw new IllegalStateException(
                    "target packet factory is closed for " + connectionProcessingId
                );
            }
            if (!connectionProcessingId.equals(input.connectionProcessingId())) {
                throw new IllegalArgumentException(
                    "attempt for " + input.connectionProcessingId()
                        + " cannot use packet factory for " + connectionProcessingId
                );
            }
            return new DeployedTargetPacketConsumer(input, targetWriteAccepted);
        }

        @Override
        // REBUILD-TRACE(G5,target): old abort/deactivate channel close.
        public CompletionStage<Void> close(ConnectionProcessingId reportedConnectionId) {
            requireOwnerThread();
            if (!connectionProcessingId.equals(reportedConnectionId)) {
                throw new IllegalArgumentException(
                    "close for " + reportedConnectionId
                        + " cannot use packet factory for " + connectionProcessingId
                );
            }
            closed = true;
            return closeCurrentChannel();
        }

        // REBUILD-TRACE(G5,target): old activateLiveChannel.
        private CompletableFuture<Channel> acquireChannel(
            DeployedTargetPacketConsumer consumer
        ) {
            requireOwnerThread();
            if (closed) {
                return CompletableFuture.failedFuture(
                    new IllegalStateException(
                        "target packet factory is closed for " + connectionProcessingId
                    )
                );
            }
            if (channel != null && channel.isActive()) {
                try {
                    consumer.initializeRequestHandlers(channel);
                    return CompletableFuture.completedFuture(channel);
                } catch (Throwable failure) {
                    return CompletableFuture.failedFuture(failure);
                }
            }
            channel = null;
            return connect(consumer);
        }

        // REBUILD-TRACE-START(G5,target): retain through the rebuild; remove in final pre-merge cleanup.
        // old createClientConnection/ClientConnectionAttempt without in-attempt backoff;
        // RequestReplayOwner.scheduleRetry owns the delay.
        // REBUILD-TRACE-END(G5,target)
        private CompletableFuture<Channel> connect(
            DeployedTargetPacketConsumer consumer
        ) {
            var result = new CompletableFuture<Channel>();
            var connectingContext =
                consumer.input.replayContext().createHttpConnectingContext();
            result.whenComplete((unused, failure) -> connectingContext.close());
            if (eventLoop.isShuttingDown()) {
                var failure = new IllegalStateException(
                    "target event loop is shutting down for " + connectionProcessingId
                );
                connectingContext.addTraceException(failure, true);
                result.completeExceptionally(failure);
                return result;
            }
            final ChannelFuture connectFuture;
            try {
                var bootstrap = new Bootstrap()
                    .group(eventLoop)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.AUTO_READ, false)
                    .handler(new ChannelInitializer<>() {
                        @Override
                        protected void initChannel(@NonNull Channel initializedChannel) {
                            initializedChannel.pipeline().addFirst(
                                CONNECTION_CLOSE_HANDLER,
                                new ConnectionClosedListenerHandler(connectionContext)
                            );
                        }
                    });
                connectFuture = bootstrap.connect(serverUri.getHost(), serverUri.getPort());
                channel = connectFuture.channel();
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
                return result;
            }
            connectFuture.addListener(ignored -> {
                try {
                    if (!connectFuture.isSuccess()) {
                        connectionContext.addFailedChannelCreation();
                        var failure = Objects.requireNonNullElseGet(
                            connectFuture.cause(),
                            () -> new IOException("target connection failed without a cause")
                        );
                        connectingContext.addTraceException(failure, true);
                        discardChannel(connectFuture.channel());
                        closeChannel(connectFuture.channel()).whenComplete(
                            (unused, closeFailure) -> result.completeExceptionally(failure)
                        );
                        return;
                    }
                    var connected = connectFuture.channel();
                    if (!connected.isActive()) {
                        var failure = new ChannelNotActiveException();
                        connectionContext.addFailedChannelCreation();
                        connectingContext.addTraceException(failure, true);
                        discardChannel(connected);
                        closeChannel(connected).whenComplete(
                            (unused, closeFailure) -> result.completeExceptionally(failure)
                        );
                        return;
                    }
                    initializeTls(connected, connectingContext, consumer, result);
                } catch (Throwable failure) {
                    discardChannel(connectFuture.channel());
                    closeChannel(connectFuture.channel()).whenComplete(
                        (unused, closeFailure) -> result.completeExceptionally(failure)
                    );
                }
            });
            return result;
        }

        // REBUILD-TRACE(G5,target): old initializeConnectionHandlers TLS branch.
        private void initializeTls(
            Channel connected,
            IReplayContexts.IRequestConnectingContext connectingContext,
            DeployedTargetPacketConsumer consumer,
            CompletableFuture<Channel> result
        ) {
            if (sslContext == null) {
                publishConnectedChannel(connected, consumer, result);
                return;
            }
            final SslHandler sslHandler;
            try {
                var sslEngine = sslContext.newEngine(connected.alloc());
                sslEngine.setUseClientMode(true);
                sslHandler = new SslHandler(sslEngine);
                addLoggingHandlerLast(connected.pipeline(), "connect");
                connected.pipeline().addLast(SSL_HANDLER, sslHandler);
            } catch (Throwable failure) {
                discardChannel(connected);
                closeChannel(connected).whenComplete(
                    (unused, closeFailure) -> result.completeExceptionally(failure)
                );
                return;
            }
            sslHandler.handshakeFuture().addListener(ignored -> {
                if (sslHandler.handshakeFuture().isSuccess()) {
                    publishConnectedChannel(connected, consumer, result);
                } else {
                    var failure = Objects.requireNonNullElseGet(
                        sslHandler.handshakeFuture().cause(),
                        () -> new IOException("TLS handshake failed without a cause")
                    );
                    connectingContext.addTraceException(failure, true);
                    discardChannel(connected);
                    closeChannel(connected).whenComplete(
                        (unused, closeFailure) -> result.completeExceptionally(failure)
                    );
                }
            });
        }

        // REBUILD-TRACE(G5,target): old active-channel publication + initializeRequestHandlers call.
        private void publishConnectedChannel(
            Channel connected,
            DeployedTargetPacketConsumer consumer,
            CompletableFuture<Channel> result
        ) {
            try {
                if (closed) {
                    closeChannel(connected).whenComplete(
                        (unused, failure) -> result.completeExceptionally(
                            new CancellationException(
                                "target packet factory closed during connection"
                            )
                        )
                    );
                    return;
                }
                channel = connected;
                consumer.initializeRequestHandlers(connected);
                result.complete(connected);
            } catch (Throwable failure) {
                discardChannel(connected);
                closeChannel(connected).whenComplete(
                    (unused, closeFailure) -> result.completeExceptionally(failure)
                );
            }
        }

        // REBUILD-TRACE(G5,target): old failed-channel close before a later retry.
        private CompletionStage<Void> invalidate(Channel failedChannel) {
            requireOwnerThread();
            if (channel == failedChannel) {
                channel = null;
            }
            return closeChannel(failedChannel);
        }

        private void discardChannel(Channel discarded) {
            if (channel == discarded) {
                channel = null;
            }
        }

        private CompletionStage<Void> closeCurrentChannel() {
            var current = channel;
            channel = null;
            return closeChannel(current);
        }

        private static CompletionStage<Void> closeChannel(Channel channel) {
            if (channel == null) {
                return CompletableFuture.completedFuture(null);
            }
            var result = new CompletableFuture<Void>();
            try {
                channel.close().addListener(ignored -> {
                    if (ignored.isSuccess()) {
                        result.complete(null);
                    } else {
                        result.completeExceptionally(
                            Objects.requireNonNullElseGet(
                                ignored.cause(),
                                () -> new IOException(
                                    "target channel close failed without a cause"
                                )
                            )
                        );
                    }
                });
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
            return result;
        }

        private static void addLoggingHandlerLast(
            ChannelPipeline pipeline,
            String name
        ) {
            PIPELINE_LOGGING.ifPresent(level ->
                pipeline.addLast(new LoggingHandler("target-" + name, level))
            );
        }

        private void requireOwnerThread() {
            if (!eventLoop.inEventLoop()) {
                throw new IllegalStateException(
                    "target packet factory access must run on its connection event loop"
                );
            }
        }

        // REBUILD-TRACE(G5,target): old channelIsInUse.
        private static boolean channelIsInUse(Channel channel) {
            var lastHandler = channel.pipeline().last();
            return !(lastHandler instanceof ConnectionClosedListenerHandler)
                && !(lastHandler instanceof SslHandler);
        }

        private static final class ChannelNotActiveException extends IOException {}

        private static final class ConnectionClosedListenerHandler
            extends ChannelInboundHandlerAdapter {
            private final IReplayContexts.ISocketContext socketContext;

            private ConnectionClosedListenerHandler(
                IReplayContexts.IConnectionContext connectionContext
            ) {
                socketContext = connectionContext.createSocketContext();
            }

            @Override
            public void channelInactive(ChannelHandlerContext context) throws Exception {
                socketContext.close();
                super.channelInactive(context);
            }

            @Override
            public void exceptionCaught(
                ChannelHandlerContext context,
                Throwable cause
            ) throws Exception {
                socketContext.addTraceException(cause, true);
                context.close();
                super.exceptionCaught(context, cause);
            }
        }

        private final class DeployedTargetPacketConsumer
            implements TargetPacketConsumer {
            private final TargetChannelPort.AttemptInput<PreparedRequest> input;
            private final IntConsumer targetWriteAccepted;
            private final AggregatedRawResponse.Builder responseBuilder;
            private final CompletableFuture<Channel> channelReady;
            private final CompletableFuture<AggregatedRawResponse> response =
                new CompletableFuture<>();
            private final OutboundRequestMethod outboundRequestMethod =
                new OutboundRequestMethod();
            private Channel requestChannel;
            private IReplayContexts.IAccumulationScope requestPhaseContext;
            private RequestPhase requestPhase = RequestPhase.NONE;
            private Throwable packetFailure;
            private int acceptedWrites;
            private boolean finalizationStarted;
            private boolean cleaned;

            private DeployedTargetPacketConsumer(
                TargetChannelPort.AttemptInput<PreparedRequest> input,
                IntConsumer targetWriteAccepted
            ) {
                this.input = input;
                this.targetWriteAccepted = targetWriteAccepted;
                this.responseBuilder = AggregatedRawResponse.builder(clock.instant());
                this.channelReady = acquireChannel(this);
            }

            // REBUILD-TRACE(G5,target): old initializeRequestHandlers.
            private void initializeRequestHandlers(Channel acquiredChannel) {
                requireOwnerThread();
                if (channelIsInUse(acquiredChannel)) {
                    throw new IllegalStateException(
                        "target channel is already in use for " + connectionProcessingId
                    );
                }
                requestChannel = acquiredChannel;
                transitionTo(RequestPhase.SENDING);
                var pipeline = acquiredChannel.pipeline();
                pipeline.addAfter(
                    CONNECTION_CLOSE_HANDLER,
                    WRITE_METER_HANDLER,
                    new WriteMeteringHandler(size -> {
                        if (size > 0) {
                            input.replayContext().onBytesSent(size);
                        }
                    })
                );
                pipeline.addAfter(
                    CONNECTION_CLOSE_HANDLER,
                    READ_METER_HANDLER,
                    new ReadMeteringHandler(size -> {
                        if (size > 0) {
                            transitionTo(RequestPhase.RECEIVING);
                            input.replayContext().onBytesReceived(size);
                        }
                    })
                );
                addLoggingHandlerLast(pipeline, "request-bytes");
                pipeline.addLast(new BacksideSnifferHandler(responseBuilder));
                pipeline.addLast(
                    new RequestMethodAwareHttpResponseDecoder(outboundRequestMethod)
                );
                pipeline.addLast(new InterimHttpResponseHandler());
                pipeline.addLast(
                    RESPONSE_WATCHER_HANDLER,
                    new BacksideHttpWatcherHandler(responseBuilder)
                );
                acquiredChannel.config().setAutoRead(true);
            }

            @Override
            // REBUILD-TRACE(G5,target): old consumeBytes compatibility boundary.
            public TrackedFuture<String, Void> consumeBytes(ByteBuf packet) {
                return sendPacket(packet).thenApply(
                    ignored -> null,
                    () -> "preserving the typed target packet result for finalization"
                );
            }

            @Override
            // REBUILD-TRACE(G5,target): old sendPacket/writePacketAndUpdateFuture.
            public TrackedFuture<String, PacketSendOutcome> sendPacket(ByteBuf packet) {
                requireOwnerThread();
                var result = new CompletableFuture<PacketSendOutcome>();
                channelReady.whenComplete((readyChannel, acquisitionFailure) ->
                    runOnOwner(
                        result,
                        () -> {
                            if (acquisitionFailure != null) {
                                packet.release();
                                settleTransportFailure(
                                    result,
                                    TrackedFuture.unwindPossibleCompletionException(
                                        acquisitionFailure
                                    ),
                                    null
                                );
                                return;
                            }
                            outboundRequestMethod.accept(packet);
                            final ChannelFuture write;
                            try {
                                write = readyChannel.writeAndFlush(packet);
                            } catch (Throwable failure) {
                                packet.release();
                                settleTransportFailure(result, failure, readyChannel);
                                return;
                            }
                            acceptedWrites++;
                            targetWriteAccepted.accept(acceptedWrites);
                            write.addListener(ignored ->
                                runOnOwner(
                                    result,
                                    () -> {
                                        if (write.isSuccess()) {
                                            result.complete(new PacketSendOutcome.PacketSubmitted());
                                        } else {
                                            settleTransportFailure(
                                                result,
                                                Objects.requireNonNullElseGet(
                                                    write.cause(),
                                                    () -> new IOException(
                                                        "target write failed without a cause"
                                                    )
                                                ),
                                                readyChannel
                                            );
                                        }
                                    }
                                )
                            );
                        }
                    )
                );
                return new TextTrackedFuture<>(result, "write one target packet");
            }

            // REBUILD-TRACE(G5,target): old classifyPacketTransportFailure plus channel teardown.
            private void settleTransportFailure(
                CompletableFuture<PacketSendOutcome> result,
                Throwable failure,
                Channel failedChannel
            ) {
                requireOwnerThread();
                var cause = TrackedFuture.unwindPossibleCompletionException(failure);
                if (!(cause instanceof IOException)
                    && !(cause instanceof ReadTimeoutException)) {
                    result.completeExceptionally(cause);
                    return;
                }
                packetFailure = cause;
                responseBuilder.addErrorCause(cause);
                var teardown = failedChannel == null
                    ? CompletableFuture.<Void>completedFuture(null)
                    : invalidate(failedChannel);
                teardown.whenComplete((unused, closeFailure) ->
                    runOnOwner(
                        result,
                        () -> {
                            if (closeFailure != null) {
                                cause.addSuppressed(
                                    TrackedFuture.unwindPossibleCompletionException(
                                        closeFailure
                                    )
                                );
                            }
                            var kind = cause instanceof ReadTimeoutException
                                ? NoTargetResponseKind.READ_TIMEOUT
                                : NoTargetResponseKind.TRANSPORT_FAILURE;
                            result.complete(
                                new PacketSendOutcome.NoTargetResponseObtained(
                                    new NoTargetResponseDiagnostic(
                                        kind,
                                        cause.toString()
                                    )
                                )
                            );
                        }
                    )
                );
            }

            @Override
            // REBUILD-TRACE(G5,target): old finalizeRequest response-watcher installation.
            public TrackedFuture<String, AggregatedRawResponse> finalizeRequest() {
                requireOwnerThread();
                if (finalizationStarted) {
                    return new TextTrackedFuture<>(
                        response,
                        "await existing target response finalization"
                    );
                }
                finalizationStarted = true;
                channelReady.whenComplete((readyChannel, acquisitionFailure) ->
                    runOnOwner(
                        response,
                        () -> {
                            if (acquisitionFailure != null || packetFailure != null) {
                                finishResponse(responseBuilder.build(), false);
                                return;
                            }
                            if (requestPhase != RequestPhase.RECEIVING) {
                                transitionTo(RequestPhase.WAITING);
                            }
                            try {
                                var pipeline = readyChannel.pipeline();
                                var timeoutPredecessor = pipeline.get(SSL_HANDLER) == null
                                    ? WRITE_METER_HANDLER
                                    : SSL_HANDLER;
                                pipeline.addAfter(
                                    timeoutPredecessor,
                                    READ_TIMEOUT_HANDLER,
                                    new ReadTimeoutHandler(
                                        readTimeout.toNanos(),
                                        TimeUnit.NANOSECONDS
                                    )
                                );
                                var watcher = Objects.requireNonNull(
                                    (BacksideHttpWatcherHandler) pipeline.get(
                                        RESPONSE_WATCHER_HANDLER
                                    ),
                                    "target response watcher is absent"
                                );
                                watcher.addCallback(value ->
                                    runOnOwner(
                                        response,
                                        () -> finishResponse(
                                            value,
                                            value.getError() == null
                                                && readyChannel.isActive()
                                        )
                                    )
                                );
                            } catch (Throwable failure) {
                                responseBuilder.addErrorCause(failure);
                                finishResponse(responseBuilder.build(), false);
                            }
                        }
                    )
                );
                return new TextTrackedFuture<>(response, "finalize target response");
            }

            // REBUILD-TRACE(G5,target): old finalizeRequest completion + deactivateChannel choice.
            private void finishResponse(
                AggregatedRawResponse value,
                boolean reusable
            ) {
                if (response.isDone()) {
                    return;
                }
                final CompletionStage<Void> cleanup;
                try {
                    cleanup = reusable
                        ? resetForReuse()
                        : invalidate(requestChannel);
                } catch (Throwable failure) {
                    closeRequestPhase();
                    response.completeExceptionally(failure);
                    return;
                }
                cleanup.whenComplete((unused, failure) ->
                    runOnOwner(
                        response,
                        () -> {
                            closeRequestPhase();
                            if (failure == null) {
                                response.complete(value);
                            } else {
                                response.completeExceptionally(
                                    TrackedFuture.unwindPossibleCompletionException(
                                        failure
                                    )
                                );
                            }
                        }
                    )
                );
            }

            // REBUILD-TRACE(G5,target): old deactivateChannel keep-alive reset.
            private CompletionStage<Void> resetForReuse() {
                requireOwnerThread();
                if (cleaned) {
                    return CompletableFuture.completedFuture(null);
                }
                cleaned = true;
                var pipeline = requestChannel.pipeline();
                removeIfPresent(pipeline, READ_TIMEOUT_HANDLER);
                removeIfPresent(pipeline, WRITE_METER_HANDLER);
                removeIfPresent(pipeline, READ_METER_HANDLER);
                while (true) {
                    var last = pipeline.last();
                    if (last == null
                        || last instanceof SslHandler
                        || last instanceof ConnectionClosedListenerHandler) {
                        break;
                    }
                    pipeline.removeLast();
                }
                requestChannel.config().setAutoRead(false);
                return CompletableFuture.completedFuture(null);
            }

            private void removeIfPresent(ChannelPipeline pipeline, String name) {
                try {
                    pipeline.remove(name);
                } catch (NoSuchElementException ignored) {
                    // A transport failure may already have torn down this handler.
                }
            }

            @Override
            // REBUILD-TRACE(G5,target): old abort's request-local state closure.
            public void abort(CancellationException cause) {
                requireOwnerThread();
                if (packetFailure == null) {
                    packetFailure = cause;
                }
                closeRequestPhase();
            }

            // REBUILD-TRACE(G5,target): old setCurrentMessageContext/getCurrentRequestSpan.
            private void transitionTo(RequestPhase next) {
                requireOwnerThread();
                if (requestPhase == next) {
                    return;
                }
                closeRequestPhase();
                requestPhaseContext = switch (next) {
                    case NONE -> null;
                    case SENDING ->
                        input.replayContext().createHttpSendingContext();
                    case WAITING ->
                        input.replayContext().createWaitingForResponseContext();
                    case RECEIVING ->
                        input.replayContext().createHttpReceivingContext();
                };
                requestPhase = next;
            }

            // REBUILD-TRACE(G5,target): old closeSpans.
            private void closeRequestPhase() {
                if (requestPhaseContext != null) {
                    requestPhaseContext.close();
                    requestPhaseContext = null;
                }
                requestPhase = RequestPhase.NONE;
            }

            private <V> void runOnOwner(
                CompletableFuture<V> completion,
                Runnable command
            ) {
                Runnable guarded = () -> {
                    try {
                        command.run();
                    } catch (Throwable failure) {
                        completion.completeExceptionally(failure);
                    }
                };
                if (eventLoop.inEventLoop()) {
                    guarded.run();
                    return;
                }
                try {
                    eventLoop.execute(guarded);
                } catch (RejectedExecutionException rejection) {
                    completion.completeExceptionally(rejection);
                }
            }
        }

        private enum RequestPhase {
            NONE,
            SENDING,
            WAITING,
            RECEIVING
        }

        private static final class OutboundRequestMethod {
            private static final String HEAD_METHOD = "HEAD";
            private int bytesRead;
            private boolean couldBeHead = true;
            private boolean complete;

            private void accept(ByteBuf packetData) {
                if (complete) {
                    return;
                }
                var bytes = packetData.duplicate();
                while (bytes.isReadable()) {
                    var next = bytes.readUnsignedByte();
                    if (bytesRead == 0 && (next == '\r' || next == '\n')) {
                        continue;
                    }
                    if (next == ' ' || next == '\t') {
                        complete = true;
                        return;
                    }
                    if (next == '\r' || next == '\n') {
                        couldBeHead = false;
                        complete = true;
                        return;
                    }
                    if (bytesRead >= HEAD_METHOD.length()
                        || next != HEAD_METHOD.charAt(bytesRead)) {
                        couldBeHead = false;
                    }
                    bytesRead++;
                }
            }

            private boolean isHead() {
                return complete
                    && couldBeHead
                    && bytesRead == HEAD_METHOD.length();
            }
        }

        private static final class RequestMethodAwareHttpResponseDecoder
            extends HttpResponseDecoder {
            private final OutboundRequestMethod requestMethod;

            private RequestMethodAwareHttpResponseDecoder(
                OutboundRequestMethod requestMethod
            ) {
                this.requestMethod = requestMethod;
            }

            @Override
            protected boolean isContentAlwaysEmpty(HttpMessage message) {
                return requestMethod.isHead() || super.isContentAlwaysEmpty(message);
            }
        }
    }

    private final class ActiveAttempt implements Attempt<AggregatedRawResponse> {
        private final AttemptInput<PreparedRequest> input;
        private final AttemptPayload payload;
        private final List<ByteBuf> packets;
        private final Instant firstPacketStart;
        private final CompletableFuture<TargetAttemptOutcome<AggregatedRawResponse>> outcome =
            new CompletableFuture<>();
        private final CompletableFuture<Void> abortCompletion = new CompletableFuture<>();
        private TargetPacketConsumer packetConsumer;
        private ScheduledFuture<?> nextPacket;
        private int acceptedWrites;
        private boolean finished;
        private boolean aborting;

        private ActiveAttempt(
            AttemptInput<PreparedRequest> input,
            AttemptPayload payload
        ) {
            this.input = input;
            this.payload = payload;
            this.packets = payload.packets().streamUnretained().toList();
            this.firstPacketStart = clock.instant();
        }

        private void start() {
            if (packets.isEmpty()) {
                throw new IllegalStateException("target attempt has no request packets");
            }
            if (packets.size() != input.preparedRequest().request().numByteBufs()) {
                throw new IllegalStateException(
                    "prepared packet count changed from "
                        + input.preparedRequest().request().numByteBufs()
                        + " to " + packets.size()
                );
            }
            packetConsumer = Objects.requireNonNull(
                packetConsumerFactory.create(input, this::targetWriteAccepted),
                "target packet consumer factory returned null"
            );
            sendPacket(0);
        }

        // REBUILD-TRACE(G5,target): old firstTargetWriteSubmitted callback, extended to final write.
        private void targetWriteAccepted(int oneBasedPacketOrdinal) {
            requireOwnerThread();
            if (finished) {
                return;
            }
            var expected = acceptedWrites + 1;
            if (oneBasedPacketOrdinal != expected || oneBasedPacketOrdinal > packets.size()) {
                fail(new IllegalStateException(
                    "target write acceptance ordinal " + oneBasedPacketOrdinal
                        + " arrived while expecting " + expected
                ));
                return;
            }
            acceptedWrites = oneBasedPacketOrdinal;
            if (acceptedWrites == 1) {
                input.writeMilestones().firstTargetWriteSubmitted(input.attemptNumber());
            }
            if (acceptedWrites == packets.size()) {
                input.writeMilestones().finalTargetWriteSubmitted(input.attemptNumber());
            }
        }

        // REBUILD-TRACE(G5,target): old consumeBytes plus captured pacing split.
        private void sendPacket(int index) {
            requireOwnerThread();
            if (finished || aborting) {
                return;
            }
            var packet = packets.get(index).retainedDuplicate();
            final org.opensearch.migrations.utils.TrackedFuture<
                String,
                TargetPacketConsumer.PacketSendOutcome
            > send;
            try {
                send = Objects.requireNonNull(
                    packetConsumer.sendPacket(packet),
                    "target packet consumer returned no send future"
                );
            } catch (Throwable failure) {
                packet.release();
                fail(failure);
                return;
            }
            send.future.whenComplete((packetOutcome, failure) ->
                continueOnOwner(() -> settlePacket(index, packetOutcome, failure))
            );
        }

        // REBUILD-TRACE(G5,target): old activeChannelFuture packet-result chain.
        private void settlePacket(
            int index,
            TargetPacketConsumer.PacketSendOutcome packetOutcome,
            Throwable failure
        ) {
            if (finished || aborting) {
                return;
            }
            if (failure != null) {
                fail(TrackedFuture.unwindPossibleCompletionException(failure));
                return;
            }
            if (packetOutcome == null) {
                fail(new NullPointerException("target packet send completed without an outcome"));
                return;
            }
            packetOutcome.visit(new TargetPacketConsumer.PacketSendOutcome.Visitor<Void>() {
                @Override
                public Void onPacketSubmitted(
                    TargetPacketConsumer.PacketSendOutcome.PacketSubmitted submitted
                ) {
                    if (acceptedWrites < index + 1) {
                        fail(new IllegalStateException(
                            "target packet " + (index + 1)
                                + " completed before Netty write acceptance was reported"
                        ));
                    } else if (index + 1 == packets.size()) {
                        finalizeResponse(null);
                    } else {
                        schedulePacket(index + 1);
                    }
                    return null;
                }

                @Override
                public Void onNoTargetResponseObtained(
                    TargetPacketConsumer.PacketSendOutcome.NoTargetResponseObtained noResponse
                ) {
                    finalizeResponse(noResponse.diagnostic());
                    return null;
                }
            });
        }

        // REBUILD-TRACE(G5,target): old scheduling moved out of RequestSenderOrchestrator.
        private void schedulePacket(int index) {
            var targetTime = firstPacketStart.plus(
                input.preparedRequest().packetInterval().multipliedBy(index)
            );
            var delay = Duration.between(clock.instant(), targetTime);
            var delayNanos = delay.isNegative() ? 0 : delay.toNanos();
            try {
                nextPacket = eventLoop.schedule(
                    () -> {
                        nextPacket = null;
                        sendPacket(index);
                    },
                    delayNanos,
                    TimeUnit.NANOSECONDS
                );
            } catch (Throwable failure) {
                fail(failure);
            }
        }

        // REBUILD-TRACE(G5,target): old finalizeRequest call and typed packet-failure preservation.
        private void finalizeResponse(NoTargetResponseDiagnostic packetFailure) {
            final org.opensearch.migrations.utils.TrackedFuture<String, AggregatedRawResponse>
                finalized;
            try {
                finalized = Objects.requireNonNull(
                    packetConsumer.finalizeRequest(),
                    "target packet consumer returned no finalization future"
                );
            } catch (Throwable failure) {
                fail(failure);
                return;
            }
            finalized.future.whenComplete((response, failure) ->
                continueOnOwner(() -> {
                    if (finished || aborting) {
                        return;
                    }
                    if (failure != null) {
                        fail(TrackedFuture.unwindPossibleCompletionException(failure));
                    } else if (packetFailure != null) {
                        finish(new TargetAttemptOutcome.NoTargetResponseObtained<>(packetFailure));
                    } else {
                        finish(classify(response));
                    }
                })
            );
        }

        // REBUILD-TRACE(G5,target): old response/error interpretation.
        private TargetAttemptOutcome<AggregatedRawResponse> classify(
            AggregatedRawResponse response
        ) {
            if (response == null) {
                throw new IllegalStateException(
                    "target attempt completed without a response value"
                );
            }
            var failure = response.getError();
            if (failure != null) {
                var cause = TrackedFuture.unwindPossibleCompletionException(failure);
                if (!(cause instanceof IOException) && !(cause instanceof ReadTimeoutException)) {
                    if (cause instanceof Error error) {
                        throw error;
                    }
                    if (cause instanceof RuntimeException runtimeFailure) {
                        throw runtimeFailure;
                    }
                    throw new IllegalStateException("unexpected target transport failure", cause);
                }
                var kind = cause instanceof ReadTimeoutException
                    ? NoTargetResponseKind.READ_TIMEOUT
                    : NoTargetResponseKind.TRANSPORT_FAILURE;
                return new TargetAttemptOutcome.NoTargetResponseObtained<>(
                    new NoTargetResponseDiagnostic(kind, cause.toString())
                );
            }
            if (response.getRawResponse() != null) {
                return new TargetAttemptOutcome.TargetResponseObtained<>(response);
            }
            return new TargetAttemptOutcome.NoTargetResponseObtained<>(
                new NoTargetResponseDiagnostic(
                    NoTargetResponseKind.MISSING_HTTP_RESPONSE,
                    "target attempt completed without an HTTP response"
                )
            );
        }

        // REBUILD-TRACE(G5,target): old successful request finalization and attempt-payload release.
        private void finish(TargetAttemptOutcome<AggregatedRawResponse> result) {
            if (finished) {
                return;
            }
            finished = true;
            Throwable cleanupFailure = releasePayload();
            activeAttempt = null;
            if (cleanupFailure == null) {
                outcome.complete(result);
            } else {
                outcome.completeExceptionally(cleanupFailure);
            }
        }

        // REBUILD-TRACE(G5,target): old exceptional future completion and payload cleanup.
        private void fail(Throwable failure) {
            if (finished) {
                return;
            }
            finished = true;
            if (nextPacket != null) {
                nextPacket.cancel(false);
                nextPacket = null;
            }
            var cleanupFailure = releasePayload();
            if (cleanupFailure != null && cleanupFailure != failure) {
                failure.addSuppressed(cleanupFailure);
            }
            activeAttempt = null;
            outcome.completeExceptionally(failure);
        }

        private Throwable releasePayload() {
            try {
                payload.close();
                return null;
            } catch (Throwable failure) {
                return failure;
            }
        }

        @Override
        public CompletionStage<TargetAttemptOutcome<AggregatedRawResponse>> outcome() {
            return outcome.minimalCompletionStage();
        }

        @Override
        // REBUILD-TRACE(G5,target): old abort + channel close, now awaited before permit release.
        public CompletionStage<Void> abort(CancellationException cause) {
            requireOwnerThread();
            if (abortCompletion.isDone()) {
                return abortCompletion.minimalCompletionStage();
            }
            aborting = true;
            if (nextPacket != null) {
                nextPacket.cancel(false);
                nextPacket = null;
            }
            try {
                if (packetConsumer != null) {
                    packetConsumer.abort(cause);
                }
            } catch (Throwable failure) {
                cause.addSuppressed(failure);
            }
            final CompletionStage<Void> close;
            try {
                close = Objects.requireNonNull(
                    packetConsumerFactory.close(connectionProcessingId),
                    "target packet consumer factory returned no abort close stage"
                );
            } catch (Throwable failure) {
                finishAbort(cause, failure);
                return abortCompletion.minimalCompletionStage();
            }
            close.whenComplete((ignored, failure) ->
                continueOnOwner(() ->
                    finishAbort(
                        cause,
                        failure == null
                            ? null
                            : TrackedFuture.unwindPossibleCompletionException(failure)
                    )
                )
            );
            return abortCompletion.minimalCompletionStage();
        }

        private void finishAbort(CancellationException cause, Throwable failure) {
            if (finished) {
                if (failure == null) {
                    abortCompletion.complete(null);
                } else {
                    abortCompletion.completeExceptionally(failure);
                }
                return;
            }
            finished = true;
            var cleanupFailure = releasePayload();
            activeAttempt = null;
            outcome.completeExceptionally(cause);
            if (failure != null) {
                if (cleanupFailure != null && cleanupFailure != failure) {
                    failure.addSuppressed(cleanupFailure);
                }
                abortCompletion.completeExceptionally(failure);
            } else if (cleanupFailure != null) {
                abortCompletion.completeExceptionally(cleanupFailure);
            } else {
                abortCompletion.complete(null);
            }
        }

        private void continueOnOwner(Runnable continuation) {
            if (eventLoop.inEventLoop()) {
                continuation.run();
                return;
            }
            try {
                eventLoop.execute(continuation);
            } catch (Throwable failure) {
                fail(failure);
            }
        }
    }
}

// REBUILD-TRACE-START(G5,source): retain through the rebuild; remove in final pre-merge cleanup.
// The inert predecessor below is the source side of the target map above. Its constructor,
// activateLiveChannel, createClientConnection*, ClientConnectionAttempt,
// createClientConnection's eventLoop.isShuttingDown guard,
// initializeConnectionHandlers, channelIsInUse, initializeRequestHandlers, consumeBytes,
// sendPacket, writePacketAndUpdateFuture, classifyPacketTransportFailure, finalizeRequest,
// deactivateChannel, abort, setCurrentMessageContext, getCurrentRequestSpan, getParentContext,
// closeSpans, OutboundRequestMethod, and RequestMethodAwareHttpResponseDecoder are each paired
// with the exact live target named in REBUILD-TRACE(G5,target). InterimHttpResponseHandler remains
// intentionally live with its POST1 removal/preservation TODO.
// REBUILD-TRACE-END(G5,source)

// REBUILD-LIMBO(G5) -- the carried predecessor members below remain inert. Javadoc is left outside
// the marked regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Cascade from the left-behind legacy set. Unresolved: ConnectionReplaySession IReplayContexts . Carried byte-identical so the behaviour stays enumerable; its milestone strips the legacy references and un-marks it.
// Un-mark a member by deleting the delimiter lines around it and splitting this region; the
// code between them is verbatim, so blame survives. Read this before writing anything new

// REBUILD-LIMBO-START(G5)
/*

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import org.opensearch.migrations.NettyFutureBinders;
import org.opensearch.migrations.replay.AggregatedRawResponse;
import org.opensearch.migrations.replay.datahandlers.http.helpers.ReadMeteringHandler;
import org.opensearch.migrations.replay.datahandlers.http.helpers.WriteMeteringHandler;
import org.opensearch.migrations.replay.datatypes.ConnectionReplaySession;
import org.opensearch.migrations.replay.netty.BacksideHttpWatcherHandler;
import org.opensearch.migrations.replay.netty.BacksideSnifferHandler;
import org.opensearch.migrations.replay.netty.InterimHttpResponseHandler;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;
import org.opensearch.migrations.tracing.IWithTypedEnclosingScope;
import org.opensearch.migrations.utils.TextTrackedFuture;
import org.opensearch.migrations.utils.TrackedFuture;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoop;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpResponseDecoder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.concurrent.ScheduledFuture;
import lombok.Lombok;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class NettyPacketToHttpConsumer implements TargetPacketConsumer {

    private static final String HEAD_METHOD = "HEAD";

*/
// REBUILD-LIMBO-END(G5)
    /**
     * Set this to of(LogLevel.ERROR) or whatever level you'd like to get logging between each handler.
     * Set this to Optional.empty() to disable intra-handler logging.
     */
// REBUILD-LIMBO-START(G5)
/*
    private static final Optional<LogLevel> PIPELINE_LOGGING_OPTIONAL = Optional.empty();

    private static final Duration MAX_WAIT_BETWEEN_CREATE_RETRIES = Duration.ofSeconds(30);

    public static final String BACKSIDE_HTTP_WATCHER_HANDLER_NAME = "BACKSIDE_HTTP_WATCHER_HANDLER";
    public static final String CONNECTION_CLOSE_HANDLER_NAME = "CONNECTION_CLOSE_HANDLER";
    public static final String SSL_HANDLER_NAME = "ssl";
    public static final String READ_TIMEOUT_HANDLER_NAME = "readTimeoutHandler";
    public static final String WRITE_COUNT_WATCHER_HANDLER_NAME = "writeCountWatcher";
    public static final String READ_COUNT_WATCHER_HANDLER_NAME = "readCountWatcher";

*/
// REBUILD-LIMBO-END(G5)
    /**
     * This is a future that chains work onto the channel.  If the value is ready, the future isn't waiting
     * on anything to happen for the channel.  If the future isn't done, something in the chain is still
     * pending.
     */
// REBUILD-LIMBO-START(G5)
/*
    TrackedFuture<String, PacketSendOutcome> activeChannelFuture;
    ConnectionReplaySession replaySession;
    private Channel channel;
    AggregatedRawResponse.Builder responseBuilder;
    IWithTypedEnclosingScope<IReplayContexts.ITargetRequestContext> currentRequestContextUnion;
    Duration readTimeoutDuration;
    private final CompletableFuture<AggregatedRawResponse> responseFuture = new CompletableFuture<>();
    private final OutboundRequestMethod outboundRequestMethod = new OutboundRequestMethod();
    private final Runnable firstTargetWriteSubmitted;
    private CancellationException cancellationCause;
    private boolean firstTargetWriteReported;
    private boolean spansClosed;

    private static final class OutboundRequestMethod {
        private int bytesRead;
        private boolean couldBeHead = true;
        private boolean complete;

        private void accept(ByteBuf packetData) {
            if (complete) {
                return;
            }
            var bytes = packetData.duplicate();
            while (bytes.isReadable()) {
                var next = bytes.readUnsignedByte();
                if (bytesRead == 0 && (next == '\r' || next == '\n')) {
                    continue;
                }
                if (next == ' ' || next == '\t') {
                    complete = true;
                    return;
                }
                if (next == '\r' || next == '\n') {
                    couldBeHead = false;
                    complete = true;
                    return;
                }
                if (bytesRead >= HEAD_METHOD.length() || next != HEAD_METHOD.charAt(bytesRead)) {
                    couldBeHead = false;
                }
                bytesRead++;
            }
        }

        private boolean isHead() {
            return complete && couldBeHead && bytesRead == HEAD_METHOD.length();
        }
    }

    private static final class RequestMethodAwareHttpResponseDecoder extends HttpResponseDecoder {
        private final OutboundRequestMethod requestMethod;

        private RequestMethodAwareHttpResponseDecoder(OutboundRequestMethod requestMethod) {
            this.requestMethod = requestMethod;
        }

        @Override
        protected boolean isContentAlwaysEmpty(HttpMessage message) {
            return requestMethod.isHead() || super.isContentAlwaysEmpty(message);
        }
    }


    private static class ConnectionClosedListenerHandler extends ChannelInboundHandlerAdapter {
        private final IReplayContexts.ISocketContext socketContext;

        ConnectionClosedListenerHandler(IReplayContexts.IChannelKeyContext channelKeyContext) {
            socketContext = channelKeyContext.createSocketContext();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            socketContext.close();
            super.channelInactive(ctx);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            socketContext.addTraceException(cause, true);
            log.atDebug().setCause(cause)
                .setMessage("Exception caught in ConnectionClosedListenerHandler for {}.  Closing channel due to exception")
                .addArgument(() -> socketContext)
                .log();
            ctx.close();
            super.exceptionCaught(ctx, cause);
        }
    }

    public NettyPacketToHttpConsumer(
        ConnectionReplaySession replaySession,
        IReplayContexts.IReplayerHttpTransactionContext ctx,
        Duration readTimeoutDuration,
        @NonNull Runnable firstTargetWriteSubmitted
    ) {
        this.replaySession = replaySession;
        this.readTimeoutDuration = readTimeoutDuration;
        this.firstTargetWriteSubmitted = firstTargetWriteSubmitted;
        this.responseBuilder = AggregatedRawResponse.builder(Instant.now());
        var parentContext = ctx.createTargetRequestContext();
        this.setCurrentMessageContext(parentContext.createHttpSendingContext());
        log.atDebug().setMessage("C'tor: incoming session={} connId={}").addArgument(replaySession).addArgument(ctx::getConnectionId).log();
        this.activeChannelFuture = activateLiveChannel().thenApply(
            ignored -> new PacketSendOutcome.PacketSubmitted(),
            () -> "target channel activated"
        );
    }

    private TrackedFuture<String, Void> activateLiveChannel() {
        if (cancellationCause != null) {
            return TextTrackedFuture.failedFuture(
                cancellationCause,
                () -> "target request was cancelled before channel activation"
            );
        }
        final var channelCtx = replaySession.getChannelKeyContext();
        return replaySession.getChannelFutureInActiveState(getParentContext())
            .thenCompose(
                channelFuture -> NettyFutureBinders.bindNettyFutureToTrackableFuture(
                    channelFuture,
                    "waiting for newly acquired channel to be ready"
                ).getDeferredFutureThroughHandle((connectFuture, t) -> {
                    if (t != null) {
                        channelCtx.addFailedChannelCreation();
                        channelCtx.addTraceException(channelFuture.cause(), true);
                        log.atWarn().setCause(t).setMessage("{} error creating channel, not retrying")
                            .addArgument(this::httpContext).log();
                        throw Lombok.sneakyThrow(t);
                    }

                    final var c = channelFuture.channel();
                    if (cancellationCause != null) {
                        c.close();
                        return TextTrackedFuture.failedFuture(
                            cancellationCause,
                            () -> "target request was cancelled while activating its channel"
                        );
                    }
                    if (c.isActive()) {
                        this.channel = c;
                        initializeRequestHandlers();
                        log.atDebug().setMessage("Channel initialized for {} signaling future")
                            .addArgument(channelCtx).log();
                        return TextTrackedFuture.completedFuture(null, () -> "Done");
                    } else {
                        // this may recurse forever - until the event loop is shutdown
                        // (see the ClientConnectionPool::shutdownNow())
                        channelCtx.addFailedChannelCreation();
                        log.atWarn()
                            .setMessage("{} Channel wasn't active, trying to create another for this request")
                            .addArgument(this::httpContext).log();
                        return activateLiveChannel();
                    }
                }, () -> "acting on ready channelFuture to retry if inactive or to return"),
                () -> "taking newly acquired channel and making it active"
            );
    }

    private <T extends IWithTypedEnclosingScope<IReplayContexts.ITargetRequestContext> & IScopedInstrumentationAttributes>
    void setCurrentMessageContext(T requestSendingContext) {
        currentRequestContextUnion = requestSendingContext;
    }

    private IScopedInstrumentationAttributes getCurrentRequestSpan() {
        return (IScopedInstrumentationAttributes) currentRequestContextUnion;
    }

    public IReplayContexts.ITargetRequestContext getParentContext() {
        return currentRequestContextUnion.getLogicalEnclosingScope();
    }

    public static ConnectionReplaySession.ChannelFutureFactory
    createClientConnectionFactory(SslContext sslContext, URI uri) {
        return (eventLoop, ctx, cancellationSignal) ->
            NettyPacketToHttpConsumer.createClientConnection(
                eventLoop,
                sslContext,
                uri,
                ctx,
                Duration.ofMillis(1),
                cancellationSignal
            );
    }

    public static class ChannelNotActiveException extends IOException { }

    public static TrackedFuture<String, ChannelFuture> createClientConnection(
        EventLoop eventLoop,
        SslContext sslContext,
        URI serverUri,
        IReplayContexts.ITargetRequestContext requestCtx,
        Duration nextRetryDuration,
        ConnectionReplaySession.CancellationSignal cancellationSignal
    ) {
        var completion = new CompletableFuture<ChannelFuture>();
        var attempt = new ClientConnectionAttempt(
            eventLoop,
            sslContext,
            serverUri,
            requestCtx,
            cancellationSignal,
            completion
        );
        attempt.start(nextRetryDuration);
        return new TextTrackedFuture<>(completion, "creating a cancellable target connection");
    }

    private static final class ClientConnectionAttempt {
        private final EventLoop eventLoop;
        private final SslContext sslContext;
        private final URI serverUri;
        private final IReplayContexts.ITargetRequestContext requestContext;
        private final ConnectionReplaySession.CancellationSignal cancellationSignal;
        private final CompletableFuture<ChannelFuture> completion;
        private ChannelFuture activeChannelFuture;
        private ScheduledFuture<?> retryFuture;

        private ClientConnectionAttempt(
            EventLoop eventLoop,
            SslContext sslContext,
            URI serverUri,
            IReplayContexts.ITargetRequestContext requestContext,
            ConnectionReplaySession.CancellationSignal cancellationSignal,
            CompletableFuture<ChannelFuture> completion
        ) {
            this.eventLoop = eventLoop;
            this.sslContext = sslContext;
            this.serverUri = serverUri;
            this.requestContext = requestContext;
            this.cancellationSignal = cancellationSignal;
            this.completion = completion;
        }

        private void start(Duration nextRetryDuration) {
            var cancellationRegistration = cancellationSignal.register(this::cancel);
            completion.whenComplete((ignored, failure) -> cancellationRegistration.close());
            runOnEventLoop(
                "start target connection attempt",
                () -> connect(nextRetryDuration)
            );
        }

        private void connect(Duration nextRetryDuration) {
            if (completion.isDone()) {
                return;
            }
            if (cancellationSignal.cause() != null) {
                cancelOnEventLoop();
                return;
            }
            if (eventLoop.isShuttingDown()) {
                fail(new IllegalStateException("EventLoop is shutting down"));
                return;
            }

            var connectingCtx = requestContext.createHttpConnectingContext();
            String host = serverUri.getHost();
            int port = serverUri.getPort();
            log.atTrace().setMessage("Active - setting up backend connection to {}:{}")
                .addArgument(host)
                .addArgument(port)
                .log();

            try {
                Bootstrap bootstrap = new Bootstrap();
                var channelKeyCtx = requestContext.getLogicalEnclosingScope().getChannelKeyContext();
                bootstrap.group(eventLoop).handler(new ChannelInitializer<>() {
                    @Override
                    protected void initChannel(@NonNull Channel ch) throws Exception {
                        ch.pipeline()
                            .addFirst(
                                CONNECTION_CLOSE_HANDLER_NAME,
                                new ConnectionClosedListenerHandler(channelKeyCtx)
                            );
                    }
                }).channel(NioSocketChannel.class).option(ChannelOption.AUTO_READ, false);

                var outboundChannelFuture = bootstrap.connect(host, port);
                activeChannelFuture = outboundChannelFuture;
                outboundChannelFuture.addListener(ignored -> {
                    try {
                        onConnectSettled(
                            outboundChannelFuture,
                            nextRetryDuration,
                            connectingCtx
                        );
                    } finally {
                        connectingCtx.close();
                    }
                });
            } catch (Throwable t) {
                connectingCtx.close();
                fail(t);
            }
        }

        private void onConnectSettled(
            ChannelFuture outboundChannelFuture,
            Duration nextRetryDuration,
            IReplayContexts.IRequestConnectingContext connectingContext
        ) {
            if (completion.isDone()) {
                activeChannelFuture = null;
                closeChannel(outboundChannelFuture);
                return;
            }
            if (cancellationSignal.cause() != null) {
                activeChannelFuture = null;
                closeChannel(outboundChannelFuture);
                cancelOnEventLoop();
                return;
            }

            Throwable failure = outboundChannelFuture.isSuccess() ? null : outboundChannelFuture.cause();
            if (failure == null && !outboundChannelFuture.channel().isActive()) {
                failure = new ChannelNotActiveException();
            }
            if (failure != null) {
                activeChannelFuture = null;
                closeChannel(outboundChannelFuture);
                log.atWarn().setCause(failure)
                    .setMessage("{} Caught exception while trying to get an active channel")
                    .addArgument(requestContext.getLogicalEnclosingScope().getChannelKeyContext())
                    .log();
                connectingContext.addTraceException(failure, true);
                if (failure instanceof Exception) {
                    scheduleRetry(nextRetryDuration);
                } else {
                    fail(failure);
                }
                return;
            }

            try {
                var initialization = initializeConnectionHandlers(outboundChannelFuture);
                initialization.future.whenComplete((channelFuture, initializationFailure) ->
                    runOnEventLoop(
                        "settle target connection initialization",
                        () -> onInitializationSettled(
                            outboundChannelFuture,
                            channelFuture,
                            initializationFailure
                        )
                    )
                );
            } catch (Throwable t) {
                closeChannel(outboundChannelFuture);
                fail(t);
            }
        }

        private void onInitializationSettled(
            ChannelFuture outboundChannelFuture,
            ChannelFuture initializedChannelFuture,
            Throwable failure
        ) {
            if (activeChannelFuture == outboundChannelFuture) {
                activeChannelFuture = null;
            }
            if (cancellationSignal.cause() != null) {
                closeChannel(outboundChannelFuture);
                cancelOnEventLoop();
                return;
            }
            if (failure != null) {
                closeChannel(outboundChannelFuture);
                fail(TrackedFuture.unwindPossibleCompletionException(failure));
                return;
            }
            if (initializedChannelFuture == null) {
                closeChannel(outboundChannelFuture);
                fail(new NullPointerException("channel initialization completed without a ChannelFuture"));
                return;
            }
            completion.complete(initializedChannelFuture);
        }

*/
// REBUILD-LIMBO-END(G5)
// REBUILD-LIMBO-ESCAPED-LINE(G5):         /*
// REBUILD-LIMBO-START(G5)
/*
         * TLS handshake failures remain terminal for this attempt rather than entering the
         * connection-acquisition retry loop.
*/
// REBUILD-LIMBO-END(G5)
// REBUILD-LIMBO-ESCAPED-LINE(G5):          */
// REBUILD-LIMBO-START(G5)
/*
        private TrackedFuture<String, ChannelFuture> initializeConnectionHandlers(
            ChannelFuture outboundChannelFuture
        ) {
            final var channel = outboundChannelFuture.channel();
            var channelKeyContext = requestContext.getLogicalEnclosingScope().getChannelKeyContext();
            log.atTrace().setMessage("{} successfully done setting up client channel for {}")
                .addArgument(channelKeyContext::getChannelKey)
                .addArgument(channel)
                .log();
            var pipeline = channel.pipeline();
            if (sslContext == null) {
                return TextTrackedFuture.completedFuture(outboundChannelFuture, () -> "");
            }
            var sslEngine = sslContext.newEngine(channel.alloc());
            sslEngine.setUseClientMode(true);
            var sslHandler = new SslHandler(sslEngine);
            addLoggingHandlerLast(pipeline, "A");
            pipeline.addLast(SSL_HANDLER_NAME, sslHandler);
            return NettyFutureBinders.bindNettyFutureToTrackableFuture(sslHandler.handshakeFuture(), () -> "")
                .thenApply(ignored -> outboundChannelFuture, () -> "");
        }

        private void scheduleRetry(Duration retryDelay) {
            if (cancellationSignal.cause() != null) {
                cancelOnEventLoop();
                return;
            }
            if (eventLoop.isShuttingDown()) {
                fail(new IllegalStateException("EventLoop is shutting down"));
                return;
            }
            var delayMillis = Math.max(0, retryDelay.toMillis());
            var nextRetryDelay = Duration.ofMillis(
                Math.min(
                    MAX_WAIT_BETWEEN_CREATE_RETRIES.toMillis(),
                    retryDelay.multipliedBy(2).toMillis()
                )
            );
            activeChannelFuture = null;
            try {
                retryFuture = eventLoop.schedule(() -> {
                    retryFuture = null;
                    connect(nextRetryDelay);
                }, delayMillis, TimeUnit.MILLISECONDS);
            } catch (Throwable t) {
                fail(t);
            }
        }

        private void cancel() {
            runOnEventLoop("cancel target connection attempt", this::cancelOnEventLoop);
        }

        private void cancelOnEventLoop() {
            var cause = cancellationSignal.cause();
            if (cause == null || completion.isDone()) {
                return;
            }
            if (retryFuture != null) {
                var retry = retryFuture;
                retryFuture = null;
                retry.cancel(false);
            }
            if (activeChannelFuture != null) {
                var channelFuture = activeChannelFuture;
                activeChannelFuture = null;
                channelFuture.cancel(false);
                closeChannel(channelFuture);
            }
            completion.completeExceptionally(cause);
        }

        private void fail(Throwable failure) {
            if (activeChannelFuture != null) {
                var channelFuture = activeChannelFuture;
                activeChannelFuture = null;
                closeChannel(channelFuture);
            }
            completion.completeExceptionally(failure);
        }

        private void closeChannel(ChannelFuture channelFuture) {
            try {
                channelFuture.channel().close();
            } catch (Throwable closeFailure) {
                log.atWarn()
                    .setCause(closeFailure)
                    .setMessage("Failed to close an incomplete target connection")
                    .log();
            }
        }

        @SuppressWarnings("java:S1181") // The connection completion must settle even if owner work throws an Error.
        private void runOnEventLoop(String operation, Runnable command) {
            Runnable guarded = () -> {
                try {
                    command.run();
                } catch (Throwable failure) {
                    fail(failure);
                }
            };
            if (eventLoop.inEventLoop()) {
                guarded.run();
                return;
            }
            try {
                eventLoop.execute(guarded);
            } catch (RejectedExecutionException rejection) {
                log.atDebug()
                    .setCause(rejection)
                    .setMessage("Event loop rejected required operation '{}'")
                    .addArgument(operation)
                    .log();
                completion.completeExceptionally(rejection);
            }
        }
    }

    private static boolean channelIsInUse(Channel c) {
        var pipeline = c.pipeline();
        var lastHandler = pipeline.last();
        if (lastHandler instanceof ConnectionClosedListenerHandler || lastHandler instanceof SslHandler) {
            assert !c.config().isAutoRead();
            return false;
        } else {
            assert c.config().isAutoRead();
            return true;
        }
    }

    private void initializeRequestHandlers() {
        assert channel.isActive();
        if (channelIsInUse(channel)) {
            throw new IllegalStateException("Channel " + channel + "is being used elsewhere already!");
        }
        var pipeline = channel.pipeline();
        // add these size counters BEFORE TLS? Notice that when removing from the pipeline, we need to be more careful
        pipeline.addAfter(
            CONNECTION_CLOSE_HANDLER_NAME,
            WRITE_COUNT_WATCHER_HANDLER_NAME,
            new WriteMeteringHandler(size -> {
                // client side, so this is the request
                if (size == 0) {
                    return;
                }
                if (!spansClosed
                    && !(this.currentRequestContextUnion instanceof IReplayContexts.IRequestSendingContext)) {
                    this.getCurrentRequestSpan().close();
                    this.setCurrentMessageContext(getParentContext().createHttpSendingContext());
                }
                getParentContext().onBytesSent(size);
            })
        );
        pipeline.addAfter(
            CONNECTION_CLOSE_HANDLER_NAME,
            READ_COUNT_WATCHER_HANDLER_NAME,
            new ReadMeteringHandler(size -> {
                // client side, so this is the response
                if (size == 0) {
                    return;
                }
                if (!spansClosed
                    && !(this.currentRequestContextUnion instanceof IReplayContexts.IReceivingHttpResponseContext)) {
                    this.getCurrentRequestSpan().close();
                    this.setCurrentMessageContext(getParentContext().createHttpReceivingContext());
                }
                getParentContext().onBytesReceived(size);
            })
        );
        addLoggingHandlerLast(pipeline, "B");
        pipeline.addLast(new BacksideSnifferHandler(responseBuilder));
        addLoggingHandlerLast(pipeline, "C");
        pipeline.addLast(new RequestMethodAwareHttpResponseDecoder(outboundRequestMethod));
        addLoggingHandlerLast(pipeline, "D");
        pipeline.addLast(new InterimHttpResponseHandler());
        pipeline.addLast(BACKSIDE_HTTP_WATCHER_HANDLER_NAME, new BacksideHttpWatcherHandler(responseBuilder));
        addLoggingHandlerLast(pipeline, "E");
        log.atTrace().setMessage("Added handlers to the pipeline: {}").addArgument(pipeline).log();

        channel.config().setAutoRead(true);
    }

    private static void addLoggingHandlerLast(ChannelPipeline pipeline, String name) {
        PIPELINE_LOGGING_OPTIONAL.ifPresent(logLevel -> pipeline.addLast(new LoggingHandler("n" + name, logLevel)));
    }

    private void deactivateChannel() {
        try {
            var pipeline = channel.pipeline();
            log.atDebug().setMessage("[{}] Resetting the pipeline for channel {} currently at: {}")
                .addArgument(this::connId)
                .addArgument(channel)
                .addArgument(pipeline)
                .log();
            for (var handlerName : new String[] { WRITE_COUNT_WATCHER_HANDLER_NAME, READ_COUNT_WATCHER_HANDLER_NAME }) {
                try {
                    pipeline.remove(handlerName);
                } catch (NoSuchElementException e) {
                    log.atWarn().setMessage("Ignoring an exception that the {}} wasn't present")
                        .addArgument(handlerName).log();
                }
            }
            while (true) {
                var lastHandler = pipeline.last();
                if (lastHandler == null
                    || lastHandler instanceof SslHandler
                    || lastHandler instanceof ConnectionClosedListenerHandler) {
                    break;
                }
                try {
                    pipeline.removeLast();
                } catch (NoSuchElementException e) {
                    break;
                }
            }
            channel.config().setAutoRead(false);
            log.atDebug().setMessage("[{}] Reset the pipeline for channel {} back to: {}")
                .addArgument(this::connId)
                .addArgument(channel)
                .addArgument(pipeline)
                .log();
        } finally {
            closeSpans();
        }
    }

    @Override
    public TrackedFuture<String, Void> consumeBytes(ByteBuf packetData) {
        return sendPacket(packetData).thenApply(
            ignored -> null,
            () -> "preserving the typed target packet result for finalization"
        );
    }

    @Override
    public TrackedFuture<String, PacketSendOutcome> sendPacket(ByteBuf packetData) {
        activeChannelFuture = activeChannelFuture.getDeferredFutureThroughHandle((v, channelException) -> {
            var failure = cancellationCause == null ? channelException : cancellationCause;
            if (failure == null) {
                if (v instanceof PacketSendOutcome.NoTargetResponseObtained) {
                    packetData.release();
                    return TextTrackedFuture.completedFuture(
                        v,
                        () -> "preserving an earlier typed no-response packet result"
                    );
                }
                outboundRequestMethod.accept(packetData);
                log.atTrace().setMessage("[{}] outboundChannelFuture is ready. Writing packets (hash={}): {}: {}")
                    .addArgument(this::connId)
                    .addArgument(() -> System.identityHashCode(packetData))
                    .addArgument(this::httpContext)
                    .addArgument(() -> packetData.toString(StandardCharsets.UTF_8))
                    .log();
                return writePacketAndUpdateFuture(packetData).whenComplete((v2, t2) ->
                    log.atTrace().setMessage("[{}] finished writing {} t={}")
                        .addArgument(this::connId)
                        .addArgument(this::httpContext)
                        .addArgument(t2)
                        .log(), () -> "");
            } else {
                log.atWarn()
                    .setMessage("[{}] outbound channel was not set up successfully, NOT writing bytes hash={}")
                    .addArgument(this::connId)
                    .addArgument(() -> System.identityHashCode(packetData))
                    .setCause(channelException)
                    .log();
                if (channel != null) {
                    channel.close();
                }
                packetData.release();
                return classifyPacketTransportFailure(failure);
            }
        }, () -> "consumeBytes - after channel is fully initialized (potentially waiting on TLS handshake)");
        log.atTrace()
            .setMessage("Setting up write of packetData[{}] hash={}.  Created future consumeBytes={}")
            .addArgument(packetData)
            .addArgument(() -> System.identityHashCode(packetData))
            .addArgument(activeChannelFuture)
            .log();
        return activeChannelFuture;
    }

    private IReplayContexts.IReplayerHttpTransactionContext httpContext() {
        return getParentContext().getLogicalEnclosingScope();
    }

    private String connId() {
        try {
            return httpContext().getConnectionId();
        } catch (Exception e) {
            return "<unknown>";
        }
    }

    private TrackedFuture<String, PacketSendOutcome> writePacketAndUpdateFuture(ByteBuf packetData) {
        final ChannelFuture writeFuture;
        try {
            writeFuture = channel.writeAndFlush(packetData);
        } catch (Throwable writeFailure) {
            try {
                packetData.release();
            } catch (Throwable releaseFailure) {
                if (releaseFailure != writeFailure) {
                    writeFailure.addSuppressed(releaseFailure);
                }
            }
            return TextTrackedFuture.failedFuture(
                writeFailure,
                () -> "target packet write failed before Netty accepted the packet"
            );
        }
        if (!firstTargetWriteReported) {
            firstTargetWriteReported = true;
            firstTargetWriteSubmitted.run();
        }
        return NettyFutureBinders.bindNettyFutureToTrackableFuture(
            writeFuture,
            "CompletableFuture that will wait for the netty future to fill in the completion value"
        ).getDeferredFutureThroughHandle((ignored, failure) -> {
            if (failure == null) {
                return TextTrackedFuture.completedFuture(
                    new PacketSendOutcome.PacketSubmitted(),
                    () -> "target packet write completed"
                );
            }
            return classifyPacketTransportFailure(failure);
        }, () -> "classifying the target packet write result"
        );
    }

    private TrackedFuture<String, PacketSendOutcome> classifyPacketTransportFailure(
        Throwable failure
    ) {
        var cause = TrackedFuture.unwindPossibleCompletionException(failure);
        if (!(cause instanceof IOException ioFailure)) {
            return TextTrackedFuture.failedFuture(
                cause,
                () -> "unexpected target packet failure"
            );
        }
        return TextTrackedFuture.completedFuture(
            new PacketSendOutcome.NoTargetResponseObtained(ioFailure),
            () -> "target transport produced no response"
        );
    }

    @Override
    public TrackedFuture<String, AggregatedRawResponse> finalizeRequest() {
        var ff = activeChannelFuture.getDeferredFutureThroughHandle((v, t) -> {
            log.atDebug().setMessage("[{}] finalization running since all prior work has completed for {}")
                .addArgument(this::connId).addArgument(() -> httpContext()).log();
            if (!spansClosed
                && !(this.currentRequestContextUnion instanceof IReplayContexts.IReceivingHttpResponseContext)) {
                this.getCurrentRequestSpan().close();
                this.setCurrentMessageContext(getParentContext().createWaitingForResponseContext());
            }

            var rval = new TrackedFuture<>(
                responseFuture,
                () -> "NettyPacketToHttpConsumer.finalizeRequest()"
            );
            if (t == null) {
                v.visit(new PacketSendOutcome.Visitor<Void>() {
                    @Override
                    public Void onPacketSubmitted(PacketSendOutcome.PacketSubmitted submitted) {
                        var pipeline = channel.pipeline();
                        var timeoutPredecessor = pipeline.get(SSL_HANDLER_NAME) == null
                            ? WRITE_COUNT_WATCHER_HANDLER_NAME
                            : SSL_HANDLER_NAME;
                        pipeline.addAfter(
                            timeoutPredecessor,
                            READ_TIMEOUT_HANDLER_NAME,
                            new ReadTimeoutHandler(
                                NettyPacketToHttpConsumer.this.readTimeoutDuration.toMillis(),
                                TimeUnit.MILLISECONDS
                            )
                        );
                        var responseWatchHandler = (BacksideHttpWatcherHandler) pipeline
                            .get(BACKSIDE_HTTP_WATCHER_HANDLER_NAME);
                        responseWatchHandler.addCallback(responseFuture::complete);
                        return null;
                    }

                    @Override
                    public Void onNoTargetResponseObtained(
                        PacketSendOutcome.NoTargetResponseObtained noResponse
                    ) {
                        responseFuture.complete(responseBuilder.build());
                        return null;
                    }
                });
            } else {
                responseFuture.complete(responseBuilder.addErrorCause(t).build());
            }
            return rval;
        }, () -> "Waiting for previous consumes to set the future")
        .map(f -> f.whenComplete((v, t) -> {
            if (channel == null) {
                log.atTrace().setMessage(
                    "finalizeRequest().whenComplete has no channel present that needs to be to deactivated.").log();
                closeSpans();
            } else {
                deactivateChannel();
            }
        }), () -> "clearing pipeline");
        log.atDebug().setMessage("[{}] Chaining finalization work off of {} for {}.  Returning finalization future={}")
            .addArgument(this::connId)
            .addArgument(activeChannelFuture)
            .addArgument(() -> httpContext())
            .addArgument(ff)
            .log();
        return ff;
    }

    @Override
    public void abort(CancellationException cause) {
        if (cancellationCause == null) {
            cancellationCause = cause;
        }
        responseFuture.complete(responseBuilder.addErrorCause(cause).build());
        // Channel activation retries connections until something stops it, so an abort can arrive while the
        // activation future is still outstanding, which means finalizeRequest may never run and the spans
        // opened in the constructor would never be closed.  Close them here; the parent http transaction
        // span will close as soon as its caller is settled, and it must not outlive this one.
        closeSpans();
    }

*/
// REBUILD-LIMBO-END(G5)
    /**
     * Closes the target request span and whichever phase span is current.  Every path that finishes a target
     * request funnels through here so that an abort racing an orderly finalization closes each span once.
     */
// REBUILD-LIMBO-START(G5)
/*
    private void closeSpans() {
        if (spansClosed) {
            return;
        }
        spansClosed = true;
        getCurrentRequestSpan().close();
        getParentContext().close();
    }
}

*/
// REBUILD-LIMBO-END(G5)
