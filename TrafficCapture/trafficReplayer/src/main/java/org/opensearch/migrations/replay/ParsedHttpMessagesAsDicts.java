package org.opensearch.migrations.replay;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpHeaders;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.coreutils.MetricsAttributeKey;
import org.opensearch.migrations.coreutils.MetricsEvent;
import org.opensearch.migrations.coreutils.MetricsLogBuilder;
import org.opensearch.migrations.coreutils.MetricsLogger;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.replay.datatypes.UniqueSourceRequestKey;

import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

    public ParsedHttpMessagesAsDicts(SourceTargetCaptureTuple tuple) {
        this(tuple, Optional.ofNullable(tuple.sourcePair));
    }

    protected ParsedHttpMessagesAsDicts(SourceTargetCaptureTuple tuple,
                                        Optional<RequestResponsePacketPair> sourcePairOp) {
        this(getSourceRequestOp(tuple.uniqueRequestKey, sourcePairOp),
                getSourceResponseOp(tuple, sourcePairOp),
                getTargetRequestOp(tuple),
                getTargetResponseOp(tuple));
    }

    private static Optional<Map<String, Object>> getTargetResponseOp(SourceTargetCaptureTuple tuple) {
        return Optional.ofNullable(tuple.targetResponseData)
                .filter(r -> !r.isEmpty())
                .map(d -> convertResponse(tuple.uniqueRequestKey, d, tuple.targetResponseDuration));
    }

    private static Optional<Map<String, Object>> getTargetRequestOp(SourceTargetCaptureTuple tuple) {
        return Optional.ofNullable(tuple.targetRequestData)
                .map(d -> d.asByteArrayStream())
                .map(d -> convertRequest(tuple.uniqueRequestKey, d.collect(Collectors.toList())));
    }

    private static Optional<Map<String, Object>> getSourceResponseOp(SourceTargetCaptureTuple tuple,
                                                                     Optional<RequestResponsePacketPair> sourcePairOp) {
        return sourcePairOp.flatMap(p ->
                Optional.ofNullable(p.responseData).flatMap(d -> Optional.ofNullable(d.packetBytes))
                        .map(d -> convertResponse(tuple.uniqueRequestKey, d,
                                // TODO: These durations are not measuring the same values!
                                Duration.between(tuple.sourcePair.requestData.getLastPacketTimestamp(),
                                        tuple.sourcePair.responseData.getLastPacketTimestamp()))));
    }

    private static Optional<Map<String, Object>> getSourceRequestOp(@NonNull UniqueSourceRequestKey diagnosticKey,
            Optional<RequestResponsePacketPair> sourcePairOp) {
        return sourcePairOp.flatMap(p ->
                Optional.ofNullable(p.requestData).flatMap(d -> Optional.ofNullable(d.packetBytes))
                        .map(d -> convertRequest(diagnosticKey, d)));
    }

    public ParsedHttpMessagesAsDicts(Optional<Map<String, Object>> sourceRequestOp1,
                                     Optional<Map<String, Object>> sourceResponseOp2,
                                     Optional<Map<String, Object>> targetRequestOp3,
                                     Optional<Map<String, Object>> targetResponseOp4) {
        this.sourceRequestOp = sourceRequestOp1;
        this.sourceResponseOp = sourceResponseOp2;
        this.targetRequestOp = targetRequestOp3;
        this.targetResponseOp = targetResponseOp4;
    }

    private static MetricsLogBuilder addMetricIfPresent(MetricsLogBuilder metricBuilder,
                                                        MetricsAttributeKey key, Optional<Object> value) {
        return value.map(v -> metricBuilder.setAttribute(key, v)).orElse(metricBuilder);
    }

    public MetricsLogBuilder buildStatusCodeMetrics(MetricsLogger logger, UniqueSourceRequestKey requestKey) {
        var builder = logger.atSuccess(MetricsEvent.STATUS_CODE_COMPARISON);
        return buildStatusCodeMetrics(builder, requestKey);
    }

    public MetricsLogBuilder buildStatusCodeMetrics(MetricsLogBuilder logBuilder, UniqueSourceRequestKey requestKey) {
        return buildStatusCodeMetrics(logBuilder, requestKey, sourceResponseOp, targetResponseOp);
    }

    public static MetricsLogBuilder buildStatusCodeMetrics(MetricsLogBuilder builder,
                                                           UniqueSourceRequestKey requestKey,
                                                           Optional<Map<String, Object>> sourceResponseOp,
                                                           Optional<Map<String, Object>> targetResponseOp) {
        var sourceStatus = sourceResponseOp.map(r -> r.get(STATUS_CODE_KEY));
        var targetStatus = targetResponseOp.map(r -> r.get(STATUS_CODE_KEY));
        builder = builder.setAttribute(MetricsAttributeKey.REQUEST_ID,
                requestKey.getTrafficStreamKey().getConnectionId() + "." + requestKey.getSourceRequestIndex());
        builder = addMetricIfPresent(builder, MetricsAttributeKey.SOURCE_HTTP_STATUS, sourceStatus);
        builder = addMetricIfPresent(builder, MetricsAttributeKey.TARGET_HTTP_STATUS, targetStatus);
        builder = addMetricIfPresent(builder, MetricsAttributeKey.HTTP_STATUS_MATCH,
                sourceStatus.flatMap(ss -> targetStatus.map(ts -> ss.equals(ts)))
                        .filter(x -> x).map(b -> (Object) 1).or(() -> Optional.<Object>of(Integer.valueOf(0))));
        builder = addMetricIfPresent(builder, MetricsAttributeKey.HTTP_METHOD,
                sourceResponseOp.map(r -> r.get("Method")));
        builder = addMetricIfPresent(builder, MetricsAttributeKey.HTTP_ENDPOINT,
                sourceResponseOp.map(r -> r.get("Request-URI")));
        return builder;
    }


    private static Stream<ByteBuf> byteToByteBufStream(List<byte[]> incoming) {
        return incoming.stream().map(Unpooled::wrappedBuffer);
    }

    private static byte[] getBytesFromByteBuf(ByteBuf buf) {
        if (buf.hasArray()) {
            return buf.array();
        } else {
            var bytes = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), bytes);
            return bytes;
        }
    }

    private static Map<String, Object> fillMap(LinkedHashMap<String, Object> map,
                                               HttpHeaders headers, ByteBuf content) {
        String base64body = Base64.getEncoder().encodeToString(getBytesFromByteBuf(content));
        content.release();
        map.put("body", base64body);
        headers.entries().stream().forEach(kvp -> map.put(kvp.getKey(), kvp.getValue()));
        return map;
    }

    private static Map<String, Object> makeSafeMap(@NonNull UniqueSourceRequestKey diagnosticKey,
                                                   Callable<Map<String, Object>> c) {
        try {
            return c.call();
        } catch (Exception e) {
            // TODO - this isn't a good design choice.
            // We should follow through with the spirit of this class and leave this as empty optional values
            log.atWarn().setMessage(()->"Putting what may be a bogus value in the output because transforming it " +
                    "into json threw an exception for "+diagnosticKey.toString()).setCause(e).log();
            return Map.of("Exception", (Object) e.toString());
        }
    }

    private static Map<String, Object> convertRequest(@NonNull UniqueSourceRequestKey diagnosticKey,
                                                      @NonNull List<byte[]> data) {
        return makeSafeMap(diagnosticKey, () -> {
            var map = new LinkedHashMap<String, Object>();
            var message = HttpByteBufFormatter.parseHttpRequestFromBufs(byteToByteBufStream(data), true);
            map.put("Request-URI", message.uri());
            map.put("Method", message.method().toString());
            map.put("HTTP-Version", message.protocolVersion().toString());
            return fillMap(map, message.headers(), message.content());
        });
    }

    private static Map<String, Object> convertResponse(@NonNull UniqueSourceRequestKey diagnosticKey,
                                                       @NonNull List<byte[]> data, Duration latency) {
        return makeSafeMap(diagnosticKey, () -> {
            var map = new LinkedHashMap<String, Object>();
            var message = HttpByteBufFormatter.parseHttpResponseFromBufs(byteToByteBufStream(data), true);
            map.put("HTTP-Version", message.protocolVersion());
            map.put(STATUS_CODE_KEY, message.status().code());
            map.put("Reason-Phrase", message.status().reasonPhrase());
            map.put(RESPONSE_TIME_MS_KEY, latency.toMillis());
            return fillMap(map, message.headers(), message.content());
        });
    }
}
