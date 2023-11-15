package org.opensearch.migrations.replay;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpHeaders;
import lombok.Lombok;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.coreutils.MetricsAttributeKey;
import org.opensearch.migrations.coreutils.MetricsEvent;
import org.opensearch.migrations.coreutils.MetricsLogBuilder;
import org.opensearch.migrations.coreutils.MetricsLogger;
import org.opensearch.migrations.replay.datatypes.UniqueSourceRequestKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
public class SummaryTupleToStreamConsumer implements Consumer<SourceTargetCaptureTuple> {
    public static final String OUTPUT_TUPLE_JSON_LOGGER = "OutputTupleJsonLogger";
    private static final MetricsLogger metricsLogger = new MetricsLogger("SourceTargetCaptureTuple");
    private static final ObjectMapper PLAIN_MAPPER = new ObjectMapper();

    Logger tupleLogger = LoggerFactory.getLogger(OUTPUT_TUPLE_JSON_LOGGER);

    private static Stream<ByteBuf> byteToByteBufStream(List<byte[]> incoming) {
        return incoming.stream().map(b -> Unpooled.wrappedBuffer(b));
    }

    private static Map<String, Object> fillMap(LinkedHashMap<String, Object> map,
                                               HttpHeaders headers, ByteBuf content) {
        String base64body = Base64.getEncoder().encodeToString(content.array());
        content.release();
        map.put("body", base64body);
        headers.entries().stream().forEach(kvp -> map.put(kvp.getKey(), kvp.getValue()));
        return map;
    }

    private Map<String, Object> makeSafeMap(Callable<Map<String, Object>> c) {
        try {
            return c.call();
        } catch (Exception e) {
            log.warn("Putting what may be a bogus value in the output because transforming it " +
                    "into json threw an exception");
            return Map.of("Exception", (Object) e.toString());
        }
    }

    private Map<String, Object> convertRequest(@NonNull List<byte[]> data) {
        return makeSafeMap(() -> {
            var map = new LinkedHashMap<String, Object>();
            var message = HttpByteBufFormatter.parseHttpRequestFromBufs(byteToByteBufStream(data), true);
            map.put("Request-URI", message.uri().toString());
            map.put("Method", message.method().toString());
            map.put("HTTP-Version", message.protocolVersion().toString());
            return fillMap(map, message.headers(), message.content());
        });
    }

    private Map<String, Object> convertResponse(@NonNull List<byte[]> data, Duration latency) {
        return makeSafeMap(() -> {
            var map = new LinkedHashMap<String, Object>();
            var message = HttpByteBufFormatter.parseHttpResponseFromBufs(byteToByteBufStream(data), true);
            map.put("HTTP-Version", message.protocolVersion());
            map.put("Status-Code", message.status().code());
            map.put("Reason-Phrase", message.status().reasonPhrase());
            map.put("response_time_ms", latency.toMillis());
            return fillMap(map, message.headers(), message.content());
        });
    }

    private static String formatUniqueRequestKey(UniqueSourceRequestKey k) {
        return k.getTrafficStreamKey().getConnectionId() + "." + k.getSourceRequestIndex();
    }

    private Map<String, Object> toJSONObject(SourceTargetCaptureTuple tuple) {
        var tupleMap = new LinkedHashMap<String, Object>();
        var sourcePairOp = Optional.ofNullable(tuple.sourcePair);
        final Optional<Map<String, Object>> sourceRequestOp = sourcePairOp.flatMap(p ->
                Optional.ofNullable(p.requestData).flatMap(d -> Optional.ofNullable(d.packetBytes))
                        .map(d -> convertRequest(d)));
        sourceRequestOp.ifPresent(r -> tupleMap.put("sourceRequest", r));
        final Optional<Map<String, Object>> sourceResponseOp = sourcePairOp.flatMap(p ->
                Optional.ofNullable(p.responseData).flatMap(d -> Optional.ofNullable(d.packetBytes))
                        .map(d -> convertResponse(d,
                                // TODO: These durations are not measuring the same values!
                                Duration.between(tuple.sourcePair.requestData.getLastPacketTimestamp(),
                                        tuple.sourcePair.responseData.getLastPacketTimestamp()))));
        sourceResponseOp.ifPresent(r -> tupleMap.put("sourceResponse", r));

        var targetRequestOp = Optional.ofNullable(tuple.targetRequestData)
                .map(d -> d.asByteArrayStream())
                .map(d -> convertRequest(d.collect(Collectors.toList())));
        targetRequestOp.ifPresent(r -> tupleMap.put("targetRequest", r));

        var targetResponseOp = Optional.ofNullable(tuple.targetResponseData)
                .filter(r -> !r.isEmpty())
                .map(d -> convertResponse(d, tuple.targetResponseDuration));
        targetResponseOp.ifPresent(r -> tupleMap.put("targetResponse", r));

        tupleMap.put("connectionId", formatUniqueRequestKey(tuple.uniqueRequestKey));
        Optional.ofNullable(tuple.errorCause).ifPresent(e -> tupleMap.put("error", e.toString()));

        emitStatusCodeMetrics(tuple.uniqueRequestKey, sourceResponseOp, targetResponseOp);

        return tupleMap;
    }

    private static void emitStatusCodeMetrics(UniqueSourceRequestKey requestKey,
                                              Optional<Map<String, Object>> sourceResponseOp,
                                              Optional<Map<String, Object>> targetResponseOp) {
        var sourceStatus = sourceResponseOp.map(r -> ((Map<String, Object>) r).get("Status-Code"));
        var targetStatus = targetResponseOp.map(r -> ((Map<String, Object>) r).get("Status-Code"));
        var builder = metricsLogger.atSuccess(MetricsEvent.STATUS_CODE_COMPARISON)
                .setAttribute(MetricsAttributeKey.REQUEST_ID,
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

        builder.emit();
    }

    private static MetricsLogBuilder addMetricIfPresent(MetricsLogBuilder metricBuilder,
                                                        MetricsAttributeKey key, Optional<Object> value) {
        return value.map(k -> metricBuilder.setAttribute(key, value)).orElse(metricBuilder);
    }

    /**
     * Writes a tuple object to an output stream as a JSON object.
     * The JSON tuple is output on one line, and has several objects: "sourceRequest", "sourceResponse",
     * "targetRequest", and "targetResponse". The "connectionId" is also included to aid in debugging.
     * An example of the format is below.
     * <p>
     * {
     * "sourceRequest": {
     * "Request-URI": XYZ,
     * "Method": XYZ,
     * "HTTP-Version": XYZ
     * "body": XYZ,
     * "header-1": XYZ,
     * "header-2": XYZ
     * },
     * "targetRequest": {
     * "Request-URI": XYZ,
     * "Method": XYZ,
     * "HTTP-Version": XYZ
     * "body": XYZ,
     * "header-1": XYZ,
     * "header-2": XYZ
     * },
     * "sourceResponse": {
     * "HTTP-Version": ABC,
     * "Status-Code": ABC,
     * "Reason-Phrase": ABC,
     * "response_time_ms": 123,
     * "body": ABC,
     * "header-1": ABC
     * },
     * "targetResponse": {
     * "HTTP-Version": ABC,
     * "Status-Code": ABC,
     * "Reason-Phrase": ABC,
     * "response_time_ms": 123,
     * "body": ABC,
     * "header-2": ABC
     * },
     * "connectionId": "0242acfffe1d0008-0000000c-00000003-0745a19f7c3c5fc9-121001ff.0"
     * }
     *
     * @param tuple the RequestResponseResponseTriple object to be converted into json and written to the stream.
     */
    @Override
    @SneakyThrows
    public void accept(SourceTargetCaptureTuple tuple) {
        tupleLogger.atInfo().setMessage(() -> {
            try {
                return PLAIN_MAPPER.writeValueAsString(toJSONObject(tuple));
            } catch (Exception e) {
                throw Lombok.sneakyThrow(e);
            }
        }).log();
    }
}
