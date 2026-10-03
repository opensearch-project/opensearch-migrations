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
import java.util.regex.Pattern;

import org.opensearch.migrations.replay.AggregatedRawResponse;
import org.opensearch.migrations.replay.HttpByteBufFormatter;
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
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.concurrent.ScheduledFuture;
import lombok.NonNull;

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
    private static final Pattern BULK_PATH =
        Pattern.compile("^(/[^/]*)?/_bulk(/.*)?$");

    public record PreparedRequest(
        @NonNull OwnedPreparedRequest request,
        @NonNull Duration packetInterval,
        @NonNull RetryRequestKind retryRequestKind
    ) implements AutoCloseable {
        public enum RetryRequestKind {
            STANDARD,
            BULK
        }

        public PreparedRequest(
            @NonNull OwnedPreparedRequest request,
            @NonNull Duration packetInterval
        ) {
            this(request, packetInterval, classifyRetryRequest(request));
        }

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

    private static PreparedRequest.RetryRequestKind classifyRetryRequest(
        OwnedPreparedRequest request
    ) {
        try (var diagnostic = request.retainDiagnosticCopy()) {
            var parsed = HttpByteBufFormatter.parseHttpRequestFromBufs(
                diagnostic.packets().streamUnretained(),
                0
            );
            return parsed != null && BULK_PATH.matcher(parsed.uri()).matches()
                ? PreparedRequest.RetryRequestKind.BULK
                : PreparedRequest.RetryRequestKind.STANDARD;
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
            public TrackedFuture<String, Void> consumeBytes(ByteBuf packet) {
                return sendPacket(packet).thenApply(
                    ignored -> null,
                    () -> "preserving the typed target packet result for finalization"
                );
            }

            @Override
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
            public void abort(CancellationException cause) {
                requireOwnerThread();
                if (packetFailure == null) {
                    packetFailure = cause;
                }
                closeRequestPhase();
            }

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
