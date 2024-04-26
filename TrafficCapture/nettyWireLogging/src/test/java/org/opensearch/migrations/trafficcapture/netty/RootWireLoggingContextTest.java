package org.opensearch.migrations.trafficcapture.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import io.opentelemetry.sdk.trace.data.SpanData;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opensearch.migrations.testutils.TestUtilities;
import org.opensearch.migrations.tracing.IContextTracker;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;
import org.opensearch.migrations.trafficcapture.StreamChannelConnectionCaptureSerializer;
import org.opensearch.migrations.trafficcapture.netty.tracing.RootWireLoggingContext;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.opensearch.migrations.trafficcapture.netty.TestStreamManager.consumeIntoArray;

@Slf4j
public class RootWireLoggingContextTest {

  private static void writeMessageAndVerifyTraces(byte[] fullTrafficBytes, Consumer<EmbeddedChannel> channelWriter, boolean shouldBlock,
                                                  List<String> expectedTraces) throws IOException {
    try (var rootContext = new TestRootContext(true, true)) {
      var streamManager = new TestStreamManager();
      var offloader = new StreamChannelConnectionCaptureSerializer("Test", "c", streamManager);

      EmbeddedChannel channel = new EmbeddedChannel(
          new ConditionallyReliableLoggingHttpHandler(rootContext,
              "n", "c", ctx -> offloader,
              new RequestCapturePredicate(), x -> shouldBlock));
      channelWriter.accept(channel);

      // we wrote the correct data to the downstream handler/channel
      var outputData = new SequenceInputStream(Collections.enumeration(channel.inboundMessages().stream()
          .map(m -> new ByteArrayInputStream(consumeIntoArray((ByteBuf) m)))
          .collect(Collectors.toList())))
          .readAllBytes();
      Assertions.assertArrayEquals(fullTrafficBytes, outputData);

      // For a non-blocking write, close the channel to force it to flush
      if (!shouldBlock) {
        channel.close().awaitUninterruptibly();
      }

      Assertions.assertNotNull(streamManager.byteBufferAtomicReference.get(),
          "This would be null if the handler didn't block until the output was written");

      var trafficStream = TrafficStream.parseFrom(streamManager.byteBufferAtomicReference.get());
      Assertions.assertTrue(trafficStream.getSubStreamCount() > 0 &&
          trafficStream.getSubStream(0).hasRead());
      var combinedTrafficPacketsStream =
          new SequenceInputStream(Collections.enumeration(trafficStream.getSubStreamList().stream()
              .filter(TrafficObservation::hasRead)
              .map(to -> new ByteArrayInputStream(to.getRead().getData().toByteArray()))
              .collect(Collectors.toList())));
      Assertions.assertArrayEquals(fullTrafficBytes, combinedTrafficPacketsStream.readAllBytes());
      Assertions.assertEquals(1, streamManager.flushCount.get());

      List<SpanData> finishedSpans = rootContext.instrumentationBundle.getFinishedSpans();
      Assertions.assertTrue(!finishedSpans.isEmpty());
      Assertions.assertTrue(!rootContext.instrumentationBundle.getFinishedMetrics().isEmpty());

      List<String> finishedSpanNames = finishedSpans.stream().map(x -> x.getName()).collect(Collectors.toList());
      expectedTraces.forEach(trace ->
          Assertions.assertTrue(finishedSpanNames.contains(trace), "finishedSpans does not contain " + trace)
      );
      if (shouldBlock) {
        Assertions.assertTrue(finishedSpanNames.contains("blocked"), "finishedSpans does not contain 'blocked' on a blocking request");
      }
    }

  }


  @Test
  public void testThatAGetProducesGatheringRequestTrace()
      throws IOException {
    byte[] fullTrafficBytes = SimpleRequests.HEALTH_CHECK.getBytes(StandardCharsets.UTF_8);
    var bb = TestUtilities.getByteBuf(fullTrafficBytes, false);
    writeMessageAndVerifyTraces(fullTrafficBytes, w -> w.writeInbound(bb), false, List.of("gatheringRequest"));
  }

  @Test
  public void testThatAPostProducesGatheringRequestTrace()
      throws IOException {
    byte[] fullTrafficBytes = SimpleRequests.SMALL_POST.getBytes(StandardCharsets.UTF_8);
    var bb = TestUtilities.getByteBuf(fullTrafficBytes, false);
    writeMessageAndVerifyTraces(fullTrafficBytes, w -> w.writeInbound(bb), true, List.of("gatheringRequest"));
  }


}
