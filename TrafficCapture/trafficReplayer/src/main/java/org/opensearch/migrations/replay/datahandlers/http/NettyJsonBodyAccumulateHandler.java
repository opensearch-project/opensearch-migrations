package org.opensearch.migrations.replay.datahandlers.http;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import org.opensearch.migrations.replay.datahandlers.JsonAccumulator;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.transform.JsonKeysForHttpMessage;

import com.fasterxml.jackson.core.JacksonException;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.event.Level;

/**
 * This accumulates HttpContent messages through a JsonAccumulator and eventually fires off a
 * fully parsed json object as parsed by the JsonAccumulator (not by a signal that end of content
 * has been reached).
 *
 * This handler currently has undefined behavior if multiple json objects are within the stream of
 * HttpContent messages.  This will also NOT fire a
 */
@Slf4j
public class NettyJsonBodyAccumulateHandler extends ChannelInboundHandlerAdapter {

    private final IReplayContexts.IRequestTransformationContext context;

    JsonAccumulator jsonAccumulator;
    HttpJsonMessageWithFaultingPayload capturedHttpJsonMessage;
    List<Object> parsedJsonObjects;
    CompositeByteBuf accumulatedBody;
    boolean jsonWasInvalid;

    @SneakyThrows
    public NettyJsonBodyAccumulateHandler(IReplayContexts.IRequestTransformationContext context) {
        this.context = context;
        this.jsonAccumulator = new JsonAccumulator();
        this.parsedJsonObjects = new ArrayList<>();
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        // use 1024 (as opposed to the default of 16) because we really don't ever want the hit of a consolidation.
        // For this buffer to continue to be used, we are far-off the happy-path.
        // Consolidating will likely burn more cycles
        //
        // Use Unpooled rather than the context allocator (`ctx.alloc()`) because this is the buffer that will
        // be passed into a transformation if there are bytes that aren't json/ndjson formatted.
        // A transformation may attempt to do manipulations or replacements of this raw ByteBuf.  It may also
        // throw an exception.  In the interest of keeping that contract as simple as possible, just use an
        // Unpooled object so that the GC can take care of this when it needs to and we won't impact the rest of
        // the system.  Lastly, this handler is parsing JSON - one more alloc on the GC isn't going to be
        // noticeable in many cases!
        accumulatedBody = Unpooled.compositeBuffer(1024);
        super.handlerAdded(ctx);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        ReferenceCountUtil.release(accumulatedBody);
        accumulatedBody = null;
        super.handlerRemoved(ctx);
    }

    @SuppressWarnings("java:S6541") // TODO: Refactor this 'brain method' to be smaller pieces
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpJsonMessageWithFaultingPayload) {
            capturedHttpJsonMessage = (HttpJsonMessageWithFaultingPayload) msg;
        } else if (msg instanceof HttpContent) {
            var contentBuf = ((HttpContent) msg).content();
            accumulatedBody.addComponent(true, contentBuf.retainedDuplicate());
            var nioBuf = contentBuf.nioBuffer();
            contentBuf.release();
            try {
                if (!jsonWasInvalid) {
                    jsonAccumulator.consumeByteBuffer(nioBuf);
                    Object nextObj;
                    while ((nextObj = jsonAccumulator.getNextTopLevelObject()) != null) {
                        parsedJsonObjects.add(nextObj);
                    }
                }
            } catch (JacksonException e) {
                log.atLevel(hasRequestContentTypeMatching(capturedHttpJsonMessage,
                        // a JacksonException for non-json data doesn't need to be surfaced to a user
                        v -> v.startsWith("application/json")) ?  Level.INFO : Level.TRACE)
                    .setCause(e)
                    .setMessage("Error parsing json body. Will pass all payload bytes directly as a ByteBuf within the payload map")
                    .log();
                jsonWasInvalid = true;
                parsedJsonObjects.clear();
            }
            if (msg instanceof LastHttpContent) {
                if (!parsedJsonObjects.isEmpty()) {
                    var payload = capturedHttpJsonMessage.payload();
                    if (parsedJsonObjects.size() > 1) {
                        payload.put(JsonKeysForHttpMessage.INLINED_NDJSON_BODIES_DOCUMENT_KEY, parsedJsonObjects);
                    } else {
                        payload.put(JsonKeysForHttpMessage.INLINED_JSON_BODY_DOCUMENT_KEY, parsedJsonObjects.get(0));
                    }
                    if (!jsonAccumulator.hasPartialValues()) {
                        context.onJsonPayloadParseSucceeded();
                    }
                }
                if (jsonAccumulator.hasPartialValues() || parsedJsonObjects.isEmpty()) {
                    if (jsonAccumulator.getTotalBytesFullyConsumed() > Integer.MAX_VALUE) {
                        throw new IndexOutOfBoundsException("JSON contents were too large " +
                            jsonAccumulator.getTotalBytesFullyConsumed() + " for a single composite ByteBuf");
                    }
                    // skip the contents that were already parsed and included in the payload as parsed json
                    // and pass the remaining stream
                    var jsonBodyByteLength = jsonWasInvalid ? 0 : (int) jsonAccumulator.getTotalBytesFullyConsumed();
                    assert accumulatedBody.readerIndex() == 0 :
                        "Didn't expect the reader index to advance since this is an internal object";

                    var leftoverBody = accumulatedBody.slice(jsonBodyByteLength,
                        accumulatedBody.readableBytes() - jsonBodyByteLength);
                    if (jsonBodyByteLength == 0 &&
                        hasRequestContentTypeMatching(capturedHttpJsonMessage, v -> !v.startsWith("text/")))
                    {
                        context.onPayloadSetBinary();
                        capturedHttpJsonMessage.payload()
                            .put(JsonKeysForHttpMessage.INLINED_BINARY_BODY_DOCUMENT_KEY,
                                leftoverBody.retainedDuplicate()
                            );
                    } else {
                        // Attempt to decode as utf-8, if not, fallback to binary body
                        try {
                            var charBuffer = decodeToUTF8(leftoverBody.nioBuffer());
                            capturedHttpJsonMessage.payload()
                                .put(JsonKeysForHttpMessage.INLINED_TEXT_BODY_DOCUMENT_KEY,
                                    charBuffer.toString());
                            context.onTextPayloadParseSucceeded();
                        } catch (CharacterCodingException e) {
                            context.onTextPayloadParseFailed();
                            log.atDebug().setCause(e).setMessage("Payload not valid utf-8, fallback to binary").log();
                            context.onPayloadSetBinary();
                            capturedHttpJsonMessage.payload()
                                .put(JsonKeysForHttpMessage.INLINED_BINARY_BODY_DOCUMENT_KEY,
                                    accumulatedBody.retainedSlice(jsonBodyByteLength,
                                        accumulatedBody.readableBytes() - jsonBodyByteLength));
                        }
                    }
                }
                accumulatedBody.release();
                accumulatedBody = null;
                ctx.fireChannelRead(capturedHttpJsonMessage);
            }
        } else {
            super.channelRead(ctx, msg);
        }
    }

    private boolean hasRequestContentTypeMatching(HttpJsonMessageWithFaultingPayload message,
                                                  Predicate<String> contentTypeFilter) {
        // ContentType not text if specified and has a value with / and that value does not start with text/
        return Optional.ofNullable(message.headers().insensitiveGet(HttpHeaderNames.CONTENT_TYPE.toString()))
            .map(s -> s.stream()
                .filter(v -> v.contains("/"))
                .filter(contentTypeFilter)
                .count() > 1
            )
            .orElse(false);
    }

    private CharBuffer decodeToUTF8(ByteBuffer buffer) throws CharacterCodingException {
        var decoder = StandardCharsets.UTF_8.newDecoder();
        decoder.onMalformedInput(CodingErrorAction.REPORT);
        decoder.onUnmappableCharacter(CodingErrorAction.REPORT);
        return decoder.decode(buffer);
    }
}
