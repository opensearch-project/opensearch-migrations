package org.opensearch.migrations.replay.datahandlers.http;

import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.AggregatedRawResponse;
import org.opensearch.migrations.replay.AggregatedTransformedResponse;
import org.opensearch.migrations.replay.Utils;
import org.opensearch.migrations.replay.datahandlers.IPacketFinalizingConsumer;
import org.opensearch.migrations.replay.util.DiagnosticTrackableCompletableFuture;
import org.opensearch.migrations.replay.util.StringTrackableCompletableFuture;
import org.opensearch.migrations.transform.JsonTransformer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;

/**
 * This class implements a packet consuming interface by using an EmbeddedChannel to write individual
 * packets through handlers that will parse the request's HTTP headers, determine what may need to
 * be done with the message, and continue to parse/handle/serialize the contents according to the
 * transformation and the headers present (such as for gzipped or chunked encodings).
 *
 * There will be a number of reasons that we need to reuse the source captured packets - both today
 * (for the output to the comparator) and in the future (for retrying transient network errors or
 * transformation errors).  With that in mind, the HttpJsonTransformer now keeps track of all of
 * the ByteBufs passed into it and can redrive them through the underlying network packet handler.
 * Cases where that would happen with this edit are where the payload wasn't being modified, nor
 * were the headers changed.  In those cases, the entire pipeline gets removed and the "baseline"
 * ones never get activated.  This class detects that the baseline (NettySendByteBufsToPacketHandlerHandler)
 * never processed data and will re-run the messages that it already had accumulated.
 *
 * A bigger question will be how to deal with network errors, where some packets have been sent to
 * the network.  If a partial response comes back, should that be reported to the user?  What if the
 * error was due to transformation, how would we be able to tell?
 */
@Slf4j
public class HttpJsonTransformingConsumer implements IPacketFinalizingConsumer<AggregatedTransformedResponse> {
    public static final int HTTP_MESSAGE_NUM_SEGMENTS = 2;
    public static final int EXPECTED_PACKET_COUNT_GUESS_FOR_HEADERS = 4;
    private final RequestPipelineOrchestrator pipelineOrchestrator;
    private final EmbeddedChannel channel;
    /**
     * Roughly try to keep track of how big each data chunk was that came into the transformer.  These values
     * are used to chop results up on the way back out.
     * Divide the chunk tracking into headers (index=0) and payload (index=1).
     */
    private final List<List<Integer>> chunkSizes;
    // This is here for recovery, in case anything goes wrong with a transformation & we want to
    // just dump it directly.  Notice that we're already storing all of the bytes until the response
    // comes back so that we can format the output that goes to the comparator.  These should be
    // backed by the exact same byte[] arrays, so the memory consumption should already be absorbed.
    private final List<ByteBuf> chunks;

    public HttpJsonTransformingConsumer(JsonTransformer transformer,
                                        IPacketFinalizingConsumer transformedPacketReceiver,
                                        String diagnosticLabel) {
        chunkSizes = new ArrayList<>(HTTP_MESSAGE_NUM_SEGMENTS);
        chunkSizes.add(new ArrayList<>(EXPECTED_PACKET_COUNT_GUESS_FOR_HEADERS));
        chunks = new ArrayList<>(HTTP_MESSAGE_NUM_SEGMENTS + EXPECTED_PACKET_COUNT_GUESS_FOR_HEADERS);
        channel = new EmbeddedChannel();
        pipelineOrchestrator = new RequestPipelineOrchestrator(chunkSizes, transformedPacketReceiver, diagnosticLabel);
        pipelineOrchestrator.addInitialHandlers(channel.pipeline(), transformer);
    }

    private NettySendByteBufsToPacketHandlerHandler getOffloadingHandler() {
        return Optional.ofNullable(channel).map(c -> (NettySendByteBufsToPacketHandlerHandler) c.pipeline()
                        .get(RequestPipelineOrchestrator.OFFLOADING_HANDLER_NAME))
                .orElse(null);
    }

    @Override
    public DiagnosticTrackableCompletableFuture<String, Void> consumeBytes(ByteBuf nextRequestPacket) {
        chunks.add(nextRequestPacket.duplicate().readerIndex(0).retain());
        chunkSizes.get(chunkSizes.size() - 1).add(nextRequestPacket.readableBytes());
        if (log.isTraceEnabled()) {
            byte[] copy = new byte[nextRequestPacket.readableBytes()];
            nextRequestPacket.duplicate().readBytes(copy);
            log.trace("HttpJsonTransformingConsumer[" + this + "]: writing into embedded channel: "
                    + new String(copy, StandardCharsets.UTF_8));
        }
        return StringTrackableCompletableFuture.completedFuture(null, ()->"initialValue")
                .map(cf->cf.thenAccept(x -> channel.writeInbound(nextRequestPacket)),
                        ()->"HttpJsonTransformingConsumer sending bytes to its EmbeddedChannel");
    }

    public DiagnosticTrackableCompletableFuture<String, AggregatedTransformedResponse> finalizeRequest() {
        var offloadingHandler = getOffloadingHandler();
        channel.close();
        if (offloadingHandler == null) {
            // the NettyDecodedHttpRequestHandler gave up and didn't bother installing the baseline handlers -
            // redrive the chunks
            return redriveWithoutTransformation(pipelineOrchestrator.packetReceiver,
                    null);
        }
        return offloadingHandler.getPacketReceiverCompletionFuture()
                .getDeferredFutureThroughHandle((v, t) -> {
                    if (t != null) {
                        if (t instanceof NoContentException) {
                            return redriveWithoutTransformation(offloadingHandler.packetReceiver, t);
                        } else {
                            throw new CompletionException(t);
                        }
                    } else {
                        return StringTrackableCompletableFuture.completedFuture(v, ()->"transformedHttpMessageValue");
                    }
                }, ()->"HttpJsonTransformingConsumer.finalizeRequest() is waiting to handle");
    }

    private DiagnosticTrackableCompletableFuture<String, AggregatedTransformedResponse>
    redriveWithoutTransformation(IPacketFinalizingConsumer<AggregatedRawResponse> packetConsumer, Throwable reason) {
        DiagnosticTrackableCompletableFuture<String,Void> consumptionChainedFuture =
                chunks.stream().collect(
                        Utils.foldLeft(DiagnosticTrackableCompletableFuture.factory.
                                        completedFuture(null, ()->"Initial value"),
                                (dcf, bb) -> dcf.thenCompose(v -> {
                                    var rval = packetConsumer.consumeBytes(bb);
                                    log.error("packetConsumer.consumeBytes()="+rval);
                                    return rval;
                                },
                                        ()->"HttpJsonTransformingConsumer.redriveWithoutTransformation collect()")));
        return consumptionChainedFuture.thenCompose(v -> packetConsumer.finalizeRequest(),
                        ()->"HttpJsonTransformingConsumer.redriveWithoutTransformation.compose()")
                .map(f->f.thenApply(r->reason == null ?
                                new AggregatedTransformedResponse(r,
                                        AggregatedTransformedResponse.HttpRequestTransformationStatus.SKIPPED) :
                                new AggregatedTransformedResponse(r, reason)),
                        ()->"HttpJsonTransformingConsumer.redriveWithoutTransformation().map()");
    }
}