package org.opensearch.migrations.replay;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import org.opensearch.migrations.replay.datatypes.TransformedPackets;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.util.NettyUtils;
import org.opensearch.migrations.replay.util.RefSafeHolder;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.base64.Base64;
import io.netty.handler.codec.base64.Base64Dialect;
import io.netty.handler.codec.http.HttpHeaders;
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

    public final Optional<Map<String, Object>> sourceRequestOp;
    public final Optional<Map<String, Object>> sourceResponseOp;
    public final Optional<Map<String, Object>> targetRequestOp;
    public final Optional<Map<String, Object>> targetResponseOp;
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

    private static Optional<Map<String, Object>> getTargetResponseOp(SourceTargetCaptureTuple tuple) {
        return Optional.ofNullable(tuple.targetResponseData)
            .filter(r -> !r.isEmpty())
            .map(d -> convertResponse(tuple.context, d, tuple.targetResponseDuration));
    }

    private static Optional<Map<String, Object>> getTargetRequestOp(SourceTargetCaptureTuple tuple) {
        return Optional.ofNullable(tuple.targetRequestData)
            .map(TransformedPackets::asByteArrayStream)
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
        Optional<Map<String, Object>> targetResponseOp4
    ) {
        this.context = context;
        this.sourceRequestOp = sourceRequestOp1;
        this.sourceResponseOp = sourceResponseOp2;
        this.targetRequestOp = targetRequestOp3;
        this.targetResponseOp = targetResponseOp4;
        fillStatusCodeMetrics(context, sourceResponseOp, targetResponseOp);
    }

    public static void fillStatusCodeMetrics(
        @NonNull IReplayContexts.ITupleHandlingContext context,
        Optional<Map<String, Object>> sourceResponseOp,
        Optional<Map<String, Object>> targetResponseOp
    ) {
        sourceResponseOp.ifPresent(r -> context.setSourceStatus((Integer) r.get(STATUS_CODE_KEY)));
        targetResponseOp.ifPresent(r -> context.setTargetStatus((Integer) r.get(STATUS_CODE_KEY)));
    }

    private static Map<String, Object> fillMap(
        LinkedHashMap<String, Object> map,
        HttpHeaders headers,
        ByteBuf content
    ) {
        try (var encodedBufHolder = RefSafeHolder.create(Base64.encode(content, false, Base64Dialect.STANDARD))) {
            var encodedBuf = encodedBufHolder.get();
            assert encodedBuf != null : "Base64.encode should not return null";
            headers.entries().forEach(kvp -> map.put(kvp.getKey(), kvp.getValue()));
            map.put("body", encodedBuf.toString(StandardCharsets.UTF_8));
            return map;
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
            log.atWarn()
                .setMessage(
                    () -> "Putting what may be a bogus value in the output because transforming it "
                        + "into json threw an exception for "
                        + context
                )
                .setCause(e)
                .log();
            return Map.of("Exception", (Object) e.toString());
        }
    }

    private static Map<String, Object> convertRequest(
        @NonNull IReplayContexts.ITupleHandlingContext context,
        @NonNull List<byte[]> data
    ) {
        return makeSafeMap(context, () -> {
            var map = new LinkedHashMap<String, Object>();
            try (
                var bufStream = NettyUtils.createRefCntNeutralCloseableByteBufStream(data);
                var messageHolder = RefSafeHolder.create(HttpByteBufFormatter.parseHttpRequestFromBufs(bufStream))
            ) {
                var message = messageHolder.get();
                if (message != null) {
                    map.put("Request-URI", message.uri());
                    map.put("Method", message.method().toString());
                    map.put("HTTP-Version", message.protocolVersion().toString());
                    context.setMethod(message.method().toString());
                    context.setEndpoint(message.uri());
                    context.setHttpVersion(message.protocolVersion().toString());
                    return fillMap(map, message.headers(), message.content());
                } else {
                    return Map.of("Exception", "Message couldn't be parsed as a full http message");
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
            var map = new LinkedHashMap<String, Object>();
            try (
                var bufStream = NettyUtils.createRefCntNeutralCloseableByteBufStream(data);
                var messageHolder = RefSafeHolder.create(HttpByteBufFormatter.parseHttpResponseFromBufs(bufStream))
            ) {
                var message = messageHolder.get();
                if (message != null) {
                    map.put("HTTP-Version", message.protocolVersion());
                    map.put(STATUS_CODE_KEY, message.status().code());
                    map.put("Reason-Phrase", message.status().reasonPhrase());
                    map.put(RESPONSE_TIME_MS_KEY, latency.toMillis());
                    return fillMap(map, message.headers(), message.content());
                } else {
                    return Map.of("Exception", "Message couldn't be parsed as a full http message");
                }
            }
        });
    }
}
