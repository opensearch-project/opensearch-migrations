package org.opensearch.migrations.replay;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.replay.ActorRequestTestUtils.RequestProcessingFixture;
import org.opensearch.migrations.replay.datahandlers.TargetPacketConsumer;
import org.opensearch.migrations.replay.datahandlers.TargetPacketConsumer.PacketSendOutcome;
import org.opensearch.migrations.replay.datatypes.ByteBufList;
import org.opensearch.migrations.replay.datatypes.ByteBufListProducer;
import org.opensearch.migrations.replay.lifecycle.AsyncPermitPool;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.PartitionGenerationId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ReplayRequestId;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.TargetAttemptOutcome;
import org.opensearch.migrations.replay.lifecycle.TargetConnectionOwner;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.utils.TextTrackedFuture;
import org.opensearch.migrations.utils.TrackedFuture;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class RequestSenderFirstWriteRetryTest extends InstrumentationTest {
    @Test
    void retryAttemptsShareOneRequestScopedFirstWriteMilestone() throws Exception {
        var targetAttempts = new AtomicInteger();
        var suppliedCallbackInvocations = new AtomicInteger();
        var retryEvaluations = new AtomicInteger();
        var fatalFailure = new CompletableFuture<Error>();
        var connectionPool = new ClientConnectionPool(
            (eventLoop, context) -> {
                throw new AssertionError("the test consumer does not acquire a channel");
            },
            "first-write-retry-test",
            1
        );
        var permitExecutor = Executors.newSingleThreadExecutor(
            runnable -> new Thread(runnable, "first-write-permit-owner")
        );
        try {
            var orchestrator = new RequestSenderOrchestrator(
                connectionPool,
                Duration.ZERO,
                Duration.ZERO,
                (session, context, firstTargetWriteSubmitted) -> {
                    targetAttempts.incrementAndGet();
                    return targetConsumer(
                        firstTargetWriteSubmitted,
                        suppliedCallbackInvocations
                    );
                },
                RequestSenderOrchestrator.noSourceTerminationObligations(),
                acceptingLifecycleSink(),
                fatalFailure::complete
            );
            var context = rootContext.getTestConnectionRequestContext(
                "retry-first-write",
                0
            );
            var partitionGeneration = partitionGeneration(context);
            var processing = new RequestProcessingFixture();
            var producer = onePacketProducer();
            var request = ActorRequestTestUtils.schedulePreparedRequest(
                orchestrator,
                partitionGeneration,
                context,
                Instant.now(),
                Duration.ZERO,
                producer,
                (requestBytes, outcome) -> {
                    Assertions.assertInstanceOf(
                        TargetAttemptOutcome.NoTargetResponseObtained.class,
                        outcome
                    );
                    var evaluation = retryEvaluations.incrementAndGet();
                    return TextTrackedFuture.completedFuture(
                        new RequestSenderOrchestrator.DeterminedTransformedResponse<>(
                            evaluation == 1
                                ? RequestSenderOrchestrator.RetryDirective.RETRY
                                : RequestSenderOrchestrator.RetryDirective.DONE,
                            "sent"
                        ),
                        () -> "deterministic retry decision"
                    );
                },
                new AsyncPermitPool(1, permitExecutor),
                processing.registration()
            );

            Assertions.assertEquals("sent", request.get(Duration.ofSeconds(5)));
            Assertions.assertEquals(2, targetAttempts.get());
            Assertions.assertEquals(2, suppliedCallbackInvocations.get());
            Assertions.assertEquals(2, retryEvaluations.get());
            Assertions.assertFalse(
                fatalFailure.isDone(),
                "the request-scoped guard must suppress the retry's duplicate callback"
            );

            Assertions.assertTrue(processing.completeTupleDurable());
            processing.lifecycleHandled().toCompletableFuture().get(
                5,
                TimeUnit.SECONDS
            );
            orchestrator.scheduleActorClose(
                context.getChannelKeyContext(),
                0,
                partitionGeneration,
                Instant.now()
            ).get(Duration.ofSeconds(5));
            Assertions.assertEquals(0, producer.refCnt());
        } finally {
            connectionPool.shutdownNow().get(5, TimeUnit.SECONDS);
            permitExecutor.shutdownNow();
            Assertions.assertTrue(
                permitExecutor.awaitTermination(5, TimeUnit.SECONDS)
            );
        }
    }

    private static TargetPacketConsumer targetConsumer(
        Runnable firstTargetWriteSubmitted,
        AtomicInteger callbackInvocations
    ) {
        return new TargetPacketConsumer() {
            @Override
            public TrackedFuture<String, PacketSendOutcome> sendPacket(ByteBuf packet) {
                packet.release();
                callbackInvocations.incrementAndGet();
                firstTargetWriteSubmitted.run();
                return TextTrackedFuture.completedFuture(
                    new PacketSendOutcome.PacketSubmitted(),
                    () -> "packet submitted"
                );
            }

            @Override
            public TrackedFuture<String, Void> consumeBytes(ByteBuf packet) {
                return sendPacket(packet).thenApply(
                    ignored -> null,
                    () -> "packet consumed"
                );
            }

            @Override
            public TrackedFuture<String, AggregatedRawResponse> finalizeRequest() {
                return TextTrackedFuture.completedFuture(
                    new AggregatedRawResponse(
                        null,
                        0,
                        Duration.ZERO,
                        List.of(),
                        null
                    ),
                    () -> "target attempt completed without an HTTP response"
                );
            }
        };
    }

    private static TargetConnectionOwner.RequestLifecycleSink acceptingLifecycleSink() {
        return new TargetConnectionOwner.RequestLifecycleSink() {
            @Override
            public java.util.concurrent.CompletionStage<Void> connectionRequestFinished(
                PartitionGenerationId partitionGenerationId,
                ReplayRequestId requestId
            ) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public java.util.concurrent.CompletionStage<Void> requestProcessingFinished(
                PartitionGenerationId partitionGenerationId,
                ReplayRequestId requestId
            ) {
                return CompletableFuture.completedFuture(null);
            }
        };
    }

    private static PartitionGenerationId partitionGeneration(
        IReplayContexts.IReplayerHttpTransactionContext context
    ) {
        var requestId = org.opensearch.migrations.replay.lifecycle.ReplayIdentity
            .replayRequestId(context.getReplayerRequestKey());
        return new PartitionGenerationId(
            new TopicPartition("first-write-retry-test", 0),
            requestId.session().sourceGeneration()
        );
    }

    private static ByteBufListProducer onePacketProducer() {
        var source = Unpooled.wrappedBuffer(new byte[] { 1 });
        var packets = new ByteBufList(source);
        source.release();
        return ByteBufListProducer.of(packets);
    }
}
