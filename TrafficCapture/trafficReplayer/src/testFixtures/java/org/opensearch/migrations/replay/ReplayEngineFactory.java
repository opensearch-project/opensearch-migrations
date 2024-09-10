package org.opensearch.migrations.replay;

import java.time.Duration;
import java.util.function.Function;

import org.opensearch.migrations.replay.datahandlers.NettyPacketToHttpConsumer;
import org.opensearch.migrations.replay.traffic.source.BufferedFlowController;

import lombok.AllArgsConstructor;

@AllArgsConstructor
public class ReplayEngineFactory implements Function<ClientConnectionPool, ReplayEngine> {
    Duration targetServerResponseTimeout;
    BufferedFlowController flowController;
    TimeShifter timeShifter;

    public ReplayEngine apply(ClientConnectionPool clientConnectionPool) {
        return new ReplayEngine(
            new RequestSenderOrchestrator(
                clientConnectionPool,
                (replaySession, ctx) ->
                    new NettyPacketToHttpConsumer(replaySession, ctx, targetServerResponseTimeout)
            ),
            flowController, timeShifter);
    }
}
