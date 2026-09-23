package org.opensearch.migrations.replay;

// REBUILD-LIMBO(G10) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Test carried byte-identical. Unresolved: BufferedFlowController ClientConnectionPool NettyPacketToHttpConsumer ReplayEngine ReplayProgressController . Per AGENTS.md section 4 an inherited test may stay broken while the architectures are partly connected; this one is restored by the milestone that rebuilds its subject, keeping its assertions conceptually stable while changing the mechanics.
// Un-mark a member by deleting the delimiter lines around it and splitting this region; the
// code between them is verbatim, so blame survives. Read this before writing anything new

// REBUILD-LIMBO-START(G10)
/*

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import org.opensearch.migrations.replay.datahandlers.NettyPacketToHttpConsumer;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ConnectionSessionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayProgressController;
import org.opensearch.migrations.replay.lifecycle.TargetConnectionOwner;
import org.opensearch.migrations.replay.traffic.source.BufferedFlowController;

public class ReplayEngineFactory implements Function<ClientConnectionPool, ReplayEngine> {
    private final Duration targetServerResponseTimeout;
    private final BufferedFlowController flowController;
    private final TimeShifter timeShifter;
    private final Dependencies dependencies;

*/
// REBUILD-LIMBO-END(G10)
    /**
     * One engine's owner dependencies.  The progress controller must not be shared with another
     * dependency bundle.
     */
// REBUILD-LIMBO-START(G10)
/*
    public static final class Dependencies {
        private final Function<ConnectionSessionKey, CompletionStage<Void>>
            sessionTerminationAcknowledger;
        private final TargetConnectionOwner.RequestLifecycleSink requestLifecycleSink;
        private final RequestSenderOrchestrator.FatalReplayHandler fatalReplayHandler;
        private final ReplayProgressController progressController;
        private final AtomicBoolean claimed = new AtomicBoolean();

        public Dependencies(
            Function<ConnectionSessionKey, CompletionStage<Void>> sessionTerminationAcknowledger,
            TargetConnectionOwner.RequestLifecycleSink requestLifecycleSink,
            RequestSenderOrchestrator.FatalReplayHandler fatalReplayHandler,
            ReplayProgressController progressController
        ) {
            this.sessionTerminationAcknowledger =
                Objects.requireNonNull(sessionTerminationAcknowledger);
            this.requestLifecycleSink = Objects.requireNonNull(requestLifecycleSink);
            this.fatalReplayHandler = Objects.requireNonNull(fatalReplayHandler);
            this.progressController = Objects.requireNonNull(progressController);
        }

        private void claim() {
            if (!claimed.compareAndSet(false, true)) {
                throw new IllegalStateException(
                    "Replay-engine owner dependencies may be claimed only once"
                );
            }
        }

        private Function<ConnectionSessionKey, CompletionStage<Void>>
            sessionTerminationAcknowledger() {
            return sessionTerminationAcknowledger;
        }

        private TargetConnectionOwner.RequestLifecycleSink requestLifecycleSink() {
            return requestLifecycleSink;
        }

        private RequestSenderOrchestrator.FatalReplayHandler fatalReplayHandler() {
            return fatalReplayHandler;
        }

        private ReplayProgressController progressController() {
            return progressController;
        }
    }

    public ReplayEngineFactory(
        Duration targetServerResponseTimeout,
        BufferedFlowController flowController,
        TimeShifter timeShifter,
        Dependencies dependencies
    ) {
        this.targetServerResponseTimeout = Objects.requireNonNull(targetServerResponseTimeout);
        this.flowController = Objects.requireNonNull(flowController);
        this.timeShifter = Objects.requireNonNull(timeShifter);
        this.dependencies = Objects.requireNonNull(dependencies);
    }

    public ReplayEngine apply(ClientConnectionPool clientConnectionPool) {
        dependencies.claim();
        return new ReplayEngine(
            new RequestSenderOrchestrator(
                clientConnectionPool,
                (replaySession, ctx, firstTargetWriteSubmitted) ->
                    new NettyPacketToHttpConsumer(
                        replaySession,
                        ctx,
                        targetServerResponseTimeout,
                        firstTargetWriteSubmitted
                    ),
                dependencies.sessionTerminationAcknowledger(),
                dependencies.requestLifecycleSink(),
                dependencies.fatalReplayHandler()
            ),
            flowController,
            timeShifter,
            dependencies.progressController()
        );
    }
}

*/
// REBUILD-LIMBO-END(G10)