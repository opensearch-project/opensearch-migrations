package org.opensearch.migrations.replay;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.List;

import org.opensearch.migrations.replay.datahandlers.NettyPacketToHttpConsumer;
import org.opensearch.migrations.replay.datatypes.ByteBufList;
import org.opensearch.migrations.replay.datatypes.ByteBufListProducer;
import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.identity.CapturedConnectionId;
import org.opensearch.migrations.replay.identity.ConnectionProcessingId;
import org.opensearch.migrations.replay.identity.KafkaRecordId;
import org.opensearch.migrations.replay.identity.PartitionGenerationId;
import org.opensearch.migrations.replay.identity.ReplayRequestId;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.TargetAttemptOutcome;
import org.opensearch.migrations.replay.lifecycle.RequestReplayOwner;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.tracing.RootReplayerContext;
import org.opensearch.migrations.testutils.CloseableLogSetup;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.opentelemetry.api.OpenTelemetry;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;


class ResultsToLogsConsumerTest {
    @Test
    void progressSummaryAndTransformedTupleRemainSeparateLoggerStreams() {
        try (
            var tupleLogs = new CloseableLogSetup("g9-output-tuple");
            var progressLogs = new CloseableLogSetup("g9-progress-summary");
            var fixture = new ResultFixture()
        ) {
            var output = new ResultsToLogsConsumer(
                tupleLogs.getTestLogger(),
                progressLogs.getTestLogger()
            );

            var tuple = output.createTupleAndReportProgress(
                fixture.tupleContext,
                fixture.result
            );
            try (var sink = output.tupleSinkFactory().create(0)) {
                sink.write(fixture.tupleContext, tuple)
                    .toCompletableFuture()
                    .join();
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }

            Assertions.assertEquals(1, progressLogs.getLogEvents().size());
            var progress = progressLogs.getLogEvents().getFirst();
            Assertions.assertTrue(progress.startsWith("0, connection.2, "));
            Assertions.assertTrue(progress.endsWith(", GET, /test"));
            Assertions.assertEquals(1, tupleLogs.getLogEvents().size());
            var json = tupleLogs.getLogEvents().getFirst();
            Assertions.assertTrue(json.contains("\"sourceRequest\""));
            Assertions.assertTrue(json.contains("\"targetResponses\""));
            Assertions.assertFalse(
                tuple.containsKey("progressSummary"),
                "progress bookkeeping must not enter tuple or S3 output"
            );
        }
    }

    private static final class ResultFixture implements AutoCloseable {
        private static final TopicPartition PARTITION =
            new TopicPartition("traffic", 0);
        private final PartitionGenerationId generation =
            new PartitionGenerationId(PARTITION, 1);
        private final ConnectionProcessingId connection =
            new ConnectionProcessingId(
                generation,
                new CapturedConnectionId("writer", "connection"),
                0
            );
        private final ReplayRequestId requestId =
            new ReplayRequestId(connection, 2);
        private final RootReplayerContext root =
            new RootReplayerContext(OpenTelemetry.noop());
        private final IReplayContexts.IKafkaRecordContext recordContext =
            root.createKafkaRecordContext(new KafkaRecordId(generation, 1), 0);
        private final IReplayContexts.ITrafficStreamsLifecycleContext trafficContext =
            recordContext.createTrafficStreamContext(1);
        private final IReplayContexts.IRequestContext requestContext =
            trafficContext.createRequestContext(requestId, Instant.EPOCH);
        private final IReplayContexts.ITupleHandlingContext tupleContext;
        private final NettyPacketToHttpConsumer.PreparedRequest preparedRequest;
        private final RequestReplayOwner.RequestResult<
            HttpMessageAndTimestamp.Request,
            NettyPacketToHttpConsumer.PreparedRequest,
            AggregatedRawResponse,
            HttpMessageAndTimestamp.Response
        > result;

        private ResultFixture() {
            requestContext.onRequestReconstituted();
            tupleContext = requestContext.createTupleContext();
            var sourceRequest = request(
                "GET /test HTTP/1.1\r\nHost: source\r\n\r\n"
            );
            var targetRequest = Unpooled.copiedBuffer(
                "GET /test HTTP/1.1\r\nHost: target\r\n\r\n",
                StandardCharsets.UTF_8
            );
            var packets = new ByteBufList(targetRequest);
            targetRequest.release();
            preparedRequest = new NettyPacketToHttpConsumer.PreparedRequest(
                ByteBufListProducer.of(packets),
                Duration.ZERO
            );
            var targetResponse = rawResponse(
                200,
                "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"
            );
            var terminal =
                new TargetAttemptOutcome.TargetResponseObtained<>(targetResponse);
            result = new RequestReplayOwner.RequestResult<>(
                requestId,
                sourceRequest,
                preparedRequest,
                HttpRequestTransformationStatus.completed(),
                List.of(terminal),
                terminal,
                new RequestReplayOwner.CompleteFinalSourceResponse<>(
                    response(
                        "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"
                    ),
                    true
                )
            );
        }

        @Override
        public void close() {
            preparedRequest.close();
            tupleContext.close();
            requestContext.close();
            trafficContext.close();
            recordContext.complete(
                IReplayContexts.RecordDisposition.COMMIT_INELIGIBLE
            );
        }

        private static HttpMessageAndTimestamp.Request request(String text) {
            var request = new HttpMessageAndTimestamp.Request(Instant.EPOCH);
            request.add(text.getBytes(StandardCharsets.UTF_8));
            request.setLastPacketTimestamp(Instant.EPOCH.plusMillis(1));
            return request;
        }

        private static HttpMessageAndTimestamp.Response response(String text) {
            var response = new HttpMessageAndTimestamp.Response(
                Instant.EPOCH.plusMillis(2)
            );
            response.add(text.getBytes(StandardCharsets.UTF_8));
            response.setLastPacketTimestamp(Instant.EPOCH.plusMillis(3));
            return response;
        }

        private static AggregatedRawResponse rawResponse(
            int status,
            String text
        ) {
            var bytes = text.getBytes(StandardCharsets.UTF_8);
            return new AggregatedRawResponse(
                new DefaultHttpResponse(
                    HttpVersion.HTTP_1_1,
                    HttpResponseStatus.valueOf(status)
                ),
                bytes.length,
                Duration.ofMillis(2),
                List.of(new AbstractMap.SimpleEntry<>(
                    Instant.EPOCH.plusMillis(2),
                    bytes
                )),
                null
            );
        }
    }
}
