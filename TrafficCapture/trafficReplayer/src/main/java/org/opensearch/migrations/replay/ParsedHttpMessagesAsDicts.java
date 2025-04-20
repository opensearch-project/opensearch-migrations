package org.opensearch.migrations.replay;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import org.opensearch.migrations.replay.HttpByteBufFormatter.HttpMessageType;
import org.opensearch.migrations.replay.datahandlers.http.HttpJsonMessageWithFaultingPayload;
import org.opensearch.migrations.replay.datahandlers.http.HttpJsonRequestWithFaultingPayload;
import org.opensearch.migrations.replay.datahandlers.http.HttpJsonResponseWithFaultingPayload;
import org.opensearch.migrations.replay.datahandlers.http.NettyDecodedHttpRequestConvertHandler;
import org.opensearch.migrations.replay.datahandlers.http.NettyDecodedHttpResponseConvertHandler;
import org.opensearch.migrations.replay.datahandlers.http.NettyJsonBodyAccumulateHandler;
import org.opensearch.migrations.replay.datatypes.ByteBufList;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.util.RefSafeHolder;
import org.opensearch.migrations.replay.util.RefSafeStreamUtils;
import org.opensearch.migrations.transform.JsonKeysForHttpMessage;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.base64.Base64Dialect;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

/**
 * TODO - This class will pull all bodies in as a byte[], even if that byte[] isn't
 * going to be used.  While in most cases, we'll likely want to emit all of the bytes
 * anyway, we might not want to deal with them as say a 10MB single array.  We should
 * build an optional streaming approach.  The optional part would be like what
 * PayloadAccessFaultingMap does (with the ability to load lazily or throw) plus a
 * stream-like interface for bodies instead of parsing the bytes.  Just leaving the
 * ByteBufs as is might make sense, though it requires callers to understand ownership.
 */
@Slf4j
public class ParsedHttpMessagesAsDicts {
    public static final String STATUS_CODE_KEY = "Status-Code";
    public static final String RESPONSE_TIME_MS_KEY = "response_time_ms";
    public static final String EXCEPTION_KEY_STRING = "Exception";
    public static final String REQUEST_URI_KEY = "Request-URI";
    public static final String METHOD_KEY = "Method";
    public static final String HTTP_VERSION_KEY = "HTTP-Version";
    public static final String PAYLOAD_KEY = "payload";

    public final Optional<Map<String, Object>> sourceRequestOp;
    public final Optional<Map<String, Object>> sourceResponseOp;
    public final Optional<Map<String, Object>> targetRequestOp;
    public final List<Map<String, Object>> targetResponseList;
    public final IReplayContexts.ITupleHandlingContext context;

    public ParsedHttpMessagesAsDicts(@NonNull SourceTargetCaptureTuple tuple) {
        this(tuple, Optional.ofNullable(tuple.sourcePair));
    }

    protected ParsedHttpMessagesAsDicts(
        @NonNull SourceTargetCaptureTuple tuple,
        Optional<RequestResponsePacketPair> sourcePairOp
    ) {
        this(
            tuple.context,
            getSourceRequestOp(tuple.context, sourcePairOp),
            getSourceResponseOp(tuple, sourcePairOp),
            getTargetRequestOp(tuple),
            getTargetResponseOp(tuple)
        );
    }

    private static List<Map<String, Object>> getTargetResponseOp(SourceTargetCaptureTuple tuple) {
        return tuple.responseList.stream()
            .map(r -> convertResponse(tuple.context, r.targetResponseData, r.targetResponseDuration))
            .collect(Collectors.toList());
    }

    private static Optional<Map<String, Object>> getTargetRequestOp(SourceTargetCaptureTuple tuple) {
        return Optional.ofNullable(tuple.targetRequestData)
            .map(ByteBufList::asByteArrayStream)
            .map(d -> convertRequest(tuple.context, d.collect(Collectors.toList())));
    }

    private static Optional<Map<String, Object>> getSourceResponseOp(
        SourceTargetCaptureTuple tuple,
        Optional<RequestResponsePacketPair> sourcePairOp
    ) {
        return sourcePairOp.flatMap(
            p -> Optional.ofNullable(p.responseData)
                .flatMap(d -> Optional.ofNullable(d.packetBytes))
                .map(
                    d -> convertResponse(
                        tuple.context,
                        d,
                        // TODO: These durations are not measuring the same values!
                        Duration.between(
                            tuple.sourcePair.requestData.getLastPacketTimestamp(),
                            tuple.sourcePair.responseData.getLastPacketTimestamp()
                        )
                    )
                )
        );
    }

    private static Optional<Map<String, Object>> getSourceRequestOp(
        @NonNull IReplayContexts.ITupleHandlingContext context,
        Optional<RequestResponsePacketPair> sourcePairOp
    ) {
        return sourcePairOp.flatMap(
            p -> Optional.ofNullable(p.requestData)
                .flatMap(d -> Optional.ofNullable(d.packetBytes))
                .map(d -> convertRequest(context, d))
        );
    }

    public ParsedHttpMessagesAsDicts(
        IReplayContexts.ITupleHandlingContext context,
        Optional<Map<String, Object>> sourceRequestOp1,
        Optional<Map<String, Object>> sourceResponseOp2,
        Optional<Map<String, Object>> targetRequestOp3,
        List<Map<String, Object>> targetResponseOps4
    ) {
        this.context = context;
        this.sourceRequestOp = sourceRequestOp1;
        this.sourceResponseOp = sourceResponseOp2;
        this.targetRequestOp = targetRequestOp3;
        this.targetResponseList = targetResponseOps4;
        fillStatusCodeMetrics(context, sourceResponseOp, targetResponseOps4);
    }

    public static void fillStatusCodeMetrics(
        @NonNull IReplayContexts.ITupleHandlingContext context,
        Optional<Map<String, Object>> sourceResponseOp,
        List<Map<String, Object>> targetResponseList
    ) {
        sourceResponseOp.ifPresent(r -> context.setSourceStatus((Integer) r.get(STATUS_CODE_KEY)));
        if (!targetResponseList.isEmpty()) {
            context.setTargetStatus((Integer) targetResponseList.get(targetResponseList.size() - 1).get(STATUS_CODE_KEY));
        }
    }
    private static String byteBufToBase64String(ByteBuf content) {
        try (var encodedBufHolder = RefSafeHolder.create(io.netty.handler.codec.base64.Base64.encode(content.duplicate(),
            false, Base64Dialect.STANDARD))) {
            var encodedBuf = encodedBufHolder.get();
            assert encodedBuf != null : "Base64.encode should not return null";
            return encodedBuf.toString(StandardCharsets.UTF_8);
        }
    }

    private static Map<String, Object> makeSafeMap(
        @NonNull IReplayContexts.ITupleHandlingContext context,
        Callable<Map<String, Object>> c
    ) {
        try {
            return c.call();
        } catch (Exception e) {
            // TODO - this isn't a good design choice.
            // We should follow through with the spirit of this class and leave this as empty optional values
            log.atWarn().setCause(e)
                .setMessage("Putting what may be a bogus value in the output because transforming it "
                        + "into json threw an exception for {}")
                .addArgument(context)
                .log();
            return Map.of(EXCEPTION_KEY_STRING, (Object) e.toString());
        }
    }

    private static Map<String, Object> convertRequest(
        @NonNull IReplayContexts.ITupleHandlingContext context,
        @NonNull List<byte[]> data
    ) {
        return makeSafeMap(context, () -> {
            try (var transformationCtx = context.getLogicalEnclosingScope().createTransformationContext();
                var messageHolder = RefSafeHolder.create(
                    RefSafeStreamUtils.refSafeTransform(
                    data.stream(),
                    Unpooled::wrappedBuffer,
                    byteBufStream ->
                        HttpByteBufFormatter.processHttpMessageFromBufs(
                            HttpMessageType.REQUEST,
                            byteBufStream,
                            new NettyDecodedHttpRequestConvertHandler(transformationCtx, false),
                            new NettyJsonBodyAccumulateHandler(transformationCtx)
                        )
                    ))) {
                var message = (HttpJsonRequestWithFaultingPayload) messageHolder.get();
                if (message != null) {
                    var map = new LinkedHashMap<>(message.headers());
                    map.put(REQUEST_URI_KEY, message.path());
                    map.put(METHOD_KEY, message.method());
                    map.put(HTTP_VERSION_KEY, message.protocol());
                    context.setMethod(message.method());
                    context.setEndpoint(message.path());
                    context.setHttpVersion(message.protocol());
                    encodeBinaryPayloadIfExists(message);
                    if (!message.payload().isEmpty()) {
                        map.put(PAYLOAD_KEY, message.payload());
                    }
                    return map;
                } else {
                    return Map.of(EXCEPTION_KEY_STRING, "Message couldn't be parsed as a full http message");
                }
            }
        });
    }

    private static Map<String, Object> convertResponse(
        @NonNull IReplayContexts.ITupleHandlingContext context,
        @NonNull List<byte[]> data,
        Duration latency
    ) {
        return makeSafeMap(context, () -> {
            try (var transformationCtx = context.getLogicalEnclosingScope().createTransformationContext();
                var messageHolder = RefSafeHolder.create(
                    RefSafeStreamUtils.refSafeTransform(
                        data.stream(),
                        Unpooled::wrappedBuffer,
                        byteBufStream ->
                            HttpByteBufFormatter.processHttpMessageFromBufs(
                                HttpMessageType.RESPONSE,
                                byteBufStream,
                                new NettyDecodedHttpResponseConvertHandler(transformationCtx),
                                new NettyJsonBodyAccumulateHandler(transformationCtx)
                            )
                    ))) {
                var message = (HttpJsonResponseWithFaultingPayload) messageHolder.get();
                if (message != null) {
                    var map = new LinkedHashMap<>(message.headers());
                    map.put(HTTP_VERSION_KEY, message.protocol());
                    map.put(STATUS_CODE_KEY, Integer.parseInt(message.code()));
                    map.put("Reason-Phrase", message.reason());
                    map.put(RESPONSE_TIME_MS_KEY, latency.toMillis());
                    context.setHttpVersion(message.protocol());
                    encodeBinaryPayloadIfExists(message);
                    if (!message.payload().isEmpty()) {
                        map.put(PAYLOAD_KEY, message.payload());
                    }
                    return map;
                } else {
                    return Map.of(EXCEPTION_KEY_STRING, "Message couldn't be parsed as a full http message");
                }
            }
        });
    }

    private static void encodeBinaryPayloadIfExists(HttpJsonMessageWithFaultingPayload message) {
        if (message.payload() != null &&
                message.payload().containsKey(JsonKeysForHttpMessage.INLINED_BINARY_BODY_DOCUMENT_KEY)) {
            var byteBufBinaryBody = (ByteBuf) message.payload().get(JsonKeysForHttpMessage.INLINED_BINARY_BODY_DOCUMENT_KEY);
            assert !message.payload().containsKey(JsonKeysForHttpMessage.INLINED_BASE64_BODY_DOCUMENT_KEY) :
                "Expected " + JsonKeysForHttpMessage.INLINED_BASE64_BODY_DOCUMENT_KEY + " to not exist.";
            message.payload().put(JsonKeysForHttpMessage.INLINED_BASE64_BODY_DOCUMENT_KEY, byteBufToBase64String(byteBufBinaryBody));
            message.payload().remove(JsonKeysForHttpMessage.INLINED_BINARY_BODY_DOCUMENT_KEY);
            byteBufBinaryBody.release();
        }
    }
}
