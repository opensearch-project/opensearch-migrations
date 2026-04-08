package org.opensearch.migrations.replay.datahandlers.http;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;

import org.opensearch.migrations.Utils;
import org.opensearch.migrations.replay.datahandlers.IPacketFinalizingConsumer;
import org.opensearch.migrations.replay.datatypes.ByteBufList;
import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.datatypes.TransformedOutputAndResult;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.transform.IAuthTransformerFactory;
import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.utils.TextTrackedFuture;
import org.opensearch.migrations.utils.TrackedFuture;

import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * This class implements a packet consuming interface by using an EmbeddedChannel to write individual
 * packets through handlers that will parse the request's HTTP headers, determine what may need to
 * be done with the message, and continue to parse/handle/serialize the contents according to the
 * transformation and the headers present (such as for gzipped or chunked encodings).
 *
 * There will be a number of reasons that we need to reuse the source captured packets - both today
 * (for analysing and comparing) and in the future (for retrying transient network errors or
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
public class HttpJsonTransformingConsumer<R> implements IPacketFinalizingConsumer<TransformedOutputAndResult<R>> {
    public static final int HTTP_MESSAGE_NUM_SEGMENTS = 2;
    public static final int EXPECTED_PACKET_COUNT_GUESS_FOR_HEADERS = 4;
    private final RequestPipelineOrchestrator<R> pipelineOrchestrator;
    private final EmbeddedChannel channel;
    private IReplayContexts.IRequestTransformationContext transformationContext;
    private Exception lastConsumeException;

    /**
     * Roughly try to keep track of how big each data chunk was that came into the transformer.  These values
     * are used to chop results up on the way back out.
     * Divide the chunk tracking into headers (index=0) and payload (index=1).
     */
    private final List<List<Integer>> chunkSizes;
    // This is here for recovery, in case anything goes wrong with a transformation & we want to
    // just dump it directly. Notice that we're already storing all of the bytes until the response
    // comes back so that we can format the output. These should be backed by the exact same
    // byte[] arrays, so the memory consumption should already be absorbed.
    private final List<ByteBuf> chunks;

    public HttpJsonTransformingConsumer(
        IJsonTransformer transformer,
        IAuthTransformerFactory authTransformerFactory,
        IPacketFinalizingConsumer<R> transformedPacketReceiver,
        IReplayContexts.IReplayerHttpTransactionContext httpTransactionContext
    ) {
        transformationContext = httpTransactionContext.createTransformationContext();
        chunkSizes = new ArrayList<>(HTTP_MESSAGE_NUM_SEGMENTS);
        chunkSizes.add(new ArrayList<>(EXPECTED_PACKET_COUNT_GUESS_FOR_HEADERS));
        chunks = new ArrayList<>(HTTP_MESSAGE_NUM_SEGMENTS + EXPECTED_PACKET_COUNT_GUESS_FOR_HEADERS);
        channel = new EmbeddedChannel();
        pipelineOrchestrator = new RequestPipelineOrchestrator<>(
            chunkSizes,
            transformedPacketReceiver,
            authTransformerFactory,
            transformationContext
        );
        pipelineOrchestrator.addInitialHandlers(channel.pipeline(), transformer);
    }

    private NettySendByteBufsToPacketHandlerHandler<R> getOffloadingHandler() {
        return Optional.ofNullable(channel)
            .map(c -> (NettySendByteBufsToPacketHandlerHandler)
                    c.pipeline().get(RequestPipelineOrchestrator.OFFLOADING_HANDLER_NAME))
            .orElse(null);
    }

    private HttpRequestDecoder getHttpRequestDecoderHandler() {
        return Optional.ofNullable(channel)
            .map(c -> (HttpRequestDecoder) c.pipeline().get(RequestPipelineOrchestrator.HTTP_REQUEST_DECODER_NAME))
            .orElse(null);
    }

    @Override
    public TrackedFuture<String, Void> consumeBytes(ByteBuf nextRequestPacket) {
        chunks.add(nextRequestPacket.retainedDuplicate());
        chunkSizes.get(chunkSizes.size() - 1).add(nextRequestPacket.readableBytes());
        log.atTrace().setMessage("HttpJsonTransformingConsumer[{}]: writing into embedded channel: {}")
            .addArgument(this)
            .addArgument(() -> nextRequestPacket.toString(StandardCharsets.UTF_8))
            .log();
        return TextTrackedFuture.completedFuture(null, () -> "initialValue")
            .map(
                cf -> cf.thenAccept(x -> channel.writeInbound(nextRequestPacket)),
                () -> "HttpJsonTransformingConsumer sending bytes to its EmbeddedChannel"
            )
            .whenComplete((v,t) -> {
                if (t instanceof Exception) { this.lastConsumeException = (Exception) t; }
            }, () -> "");
    }

    public TrackedFuture<String, TransformedOutputAndResult<R>> finalizeRequest() {
        try {
            channel.checkException();
            if (lastConsumeException != null) {
                throw lastConsumeException;
            }
            if (getHttpRequestDecoderHandler() == null) { // LastHttpContent won't be sent
                channel.writeInbound(new EndOfInput());   // so send our own version of 'EOF'
            }
        } catch (Exception e) {
            this.transformationContext.addCaughtException(e);
            log.atWarn().setCause(e)
                .setMessage("Caught exception when sending the end of content").log();
            channel.finishAndReleaseAll();
            channel.close();
            return redriveWithoutTransformation(pipelineOrchestrator.packetReceiver, e);
        }

        if (pipelineOrchestrator.isDeferredSigningMode()) {
            return finalizeDeferredSigning();
        }

        return finalizeNormalPath();
    }

    @SuppressWarnings("unchecked")
    private TrackedFuture<String, TransformedOutputAndResult<R>> finalizeDeferredSigning() {
        try {
            // Read unsigned output from the embedded channel's inbound queue.
            // After compressor + stream-to-bytebuf, the queue contains:
            // HttpJsonRequestWithFaultingPayload (with Content-Length/Transfer-Encoding set)
            // + body ByteBufs (compressed, chunked/fixed-encoded)
            // + possibly other messages (HttpRequest from decoder, LastHttpContent)
            HttpJsonRequestWithFaultingPayload unsignedHeaders = null;
            var bodyByteBufs = new ArrayList<ByteBuf>();
            Object msg;
            while ((msg = channel.readInbound()) != null) {
                if (msg instanceof HttpJsonRequestWithFaultingPayload) {
                    unsignedHeaders = (HttpJsonRequestWithFaultingPayload) msg;
                } else if (msg instanceof ByteBuf) {
                    bodyByteBufs.add(((ByteBuf) msg).retain());
                } else {
                    ReferenceCountUtil.release(msg);
                }
            }

            if (unsignedHeaders == null) {
                // No output from auth signer — fall back to redrive
                bodyByteBufs.forEach(ByteBuf::release);
                channel.finishAndReleaseAll();
                channel.close();
                return redriveWithoutTransformation(pipelineOrchestrator.packetReceiver, null);
            }

            // Get the reentrant SignatureProducer from the auth signer
            var authSigner = channel.pipeline().get(NettyJsonContentAuthSigner.class);
            var signatureProducer = authSigner.getSignatureProducer();

            // Save a read-only deep copy of the unsigned headers as the template
            var templateHeaders = deepCopyHeaders(unsignedHeaders);

            var snapshotChunkSizes = Collections.unmodifiableList(chunkSizes);
            var producer = new SigningByteBufListProducer(
                templateHeaders, bodyByteBufs, signatureProducer, snapshotChunkSizes,
                signedHeaders -> serializeHeadersAndPrependToBody(signedHeaders, bodyByteBufs, snapshotChunkSizes)
            );

            transformationContext.onTransformSuccess();
            transformationContext.close();

            // R is ByteBufListProducer in production — this cast is safe because
            // SigningByteBufListProducer extends ByteBufListProducer
            var result = (TransformedOutputAndResult<R>) (TransformedOutputAndResult<?>)
                new TransformedOutputAndResult<>(producer, HttpRequestTransformationStatus.completed());
            return TextTrackedFuture.completedFuture(result,
                () -> "deferred signing producer created");
        } finally {
            channel.finishAndReleaseAll();
            channel.close();
        }
    }


    static ByteBufList serializeHeadersAndPrependToBody(
        HttpJsonRequestWithFaultingPayload signedHeaders,
        List<ByteBuf> bodyByteBufs,
        List<List<Integer>> chunkSizes
    ) {
        // Only run NettyJsonToByteBufHandler to serialize headers into ByteBuf(s).
        // Body ByteBufs are already compressed and serialized — just append them.
        var ch = new EmbeddedChannel(
            new NettyJsonToByteBufHandler(Collections.unmodifiableList(chunkSizes))
        );
        ch.writeInbound(signedHeaders);
        ch.writeInbound(io.netty.handler.codec.http.LastHttpContent.EMPTY_LAST_CONTENT);
        ch.finish();

        var packets = new ByteBufList();
        Object out;
        while ((out = ch.readInbound()) != null) {
            if (out instanceof ByteBuf) {
                packets.add((ByteBuf) out);
                ((ByteBuf) out).release();
            } else {
                ReferenceCountUtil.release(out);
            }
        }
        // Append the pre-serialized body ByteBufs
        for (var bodyBuf : bodyByteBufs) {
            packets.add(bodyBuf.duplicate());
        }
        return packets;
    }
    static HttpJsonRequestWithFaultingPayload deepCopyHeaders(HttpJsonRequestWithFaultingPayload original) {
        var copy = new HttpJsonRequestWithFaultingPayload();
        copy.setMethod(original.method());
        copy.setPath(original.path());
        copy.setProtocol(original.protocol());
        // Deep copy headers preserving LinkedHashMap insertion order
        var originalStrictMap = original.headers().asStrictMap();
        var newStrictMap = new StrictCaseInsensitiveHttpHeadersMap();
        for (var entry : originalStrictMap.entrySet()) {
            newStrictMap.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        copy.setHeaders(new ListKeyAdaptingCaseInsensitiveHeadersMap(newStrictMap));
        return copy;
    }

    private TrackedFuture<String, TransformedOutputAndResult<R>> finalizeNormalPath() {
        var offloadingHandler = getOffloadingHandler();
        channel.finishAndReleaseAll();
        var cf = channel.close();
        if (cf.cause() != null) {
            log.atInfo().setCause(cf.cause()).setMessage("Exception encountered during write").log();
        }

        if (offloadingHandler == null) {
            return redriveWithoutTransformation(pipelineOrchestrator.packetReceiver, null);
        }
        return offloadingHandler.getPacketReceiverCompletionFuture().getDeferredFutureThroughHandle((v, t) -> {
            if (t != null) {
                transformationContext.onTransformFailure();
                t = TrackedFuture.unwindPossibleCompletionException(t);
                if (t instanceof NoContentException) {
                    return redriveWithoutTransformation(offloadingHandler.packetReceiver, t);
                } else {
                    transformationContext.close();
                    throw new CompletionException(t);
                }
            } else {
                transformationContext.close();
                transformationContext.onTransformSuccess();
                return TextTrackedFuture.completedFuture(v, () -> "transformedHttpMessageValue");
            }
        }, () -> "HttpJsonTransformingConsumer.finalizeRequest() is waiting to handle");
    }

    private TrackedFuture<String, TransformedOutputAndResult<R>> redriveWithoutTransformation(
        IPacketFinalizingConsumer<R> packetConsumer,
        Throwable reason
    ) {
        var consumptionChainedFuture = chunks.stream()
            .collect(
                Utils.foldLeft(
                    TrackedFuture.Factory.completedFuture((Void) null, () -> "Initial value"),
                    (tf, bb) -> tf.thenCompose(
                        v -> packetConsumer.consumeBytes(bb),
                        () -> "HttpJsonTransformingConsumer.redriveWithoutTransformation collect()"
                    )
                )
            );
        var finalizedFuture = consumptionChainedFuture.thenCompose(
            v -> packetConsumer.finalizeRequest(),
            () -> "HttpJsonTransformingConsumer.redriveWithoutTransformation.compose()"
        );
        return finalizedFuture.thenApply(
            r -> new TransformedOutputAndResult<>(r, makeStatusForRedrive(reason)),
            () -> "redrive final packaging"
        ).whenComplete((v, t) -> {
            if (t != null || (v != null && v.transformationStatus.isError())) {
                transformationContext.onTransformFailure();
            } else {
                transformationContext.onTransformSkip();
            }
            transformationContext.close();
        }, () -> "HttpJsonTransformingConsumer.redriveWithoutTransformation().map()");
    }

    private static HttpRequestTransformationStatus makeStatusForRedrive(Throwable reason) {
        return reason == null
            ? HttpRequestTransformationStatus.skipped() : HttpRequestTransformationStatus.makeError(reason);
    }
}
