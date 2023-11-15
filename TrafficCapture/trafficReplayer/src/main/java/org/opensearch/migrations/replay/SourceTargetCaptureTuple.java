package org.opensearch.migrations.replay;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpHeaders;
import lombok.Lombok;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.datatypes.TransformedPackets;
import org.opensearch.migrations.replay.datatypes.UniqueSourceRequestKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
public class SourceTargetCaptureTuple implements AutoCloseable {
    public static final String OUTPUT_TUPLE_JSON_LOGGER = "OutputTupleJsonLogger";
    private static final ObjectMapper PLAIN_MAPPER = new ObjectMapper();

    final UniqueSourceRequestKey uniqueRequestKey;
    final RequestResponsePacketPair sourcePair;
    final TransformedPackets targetRequestData;
    final List<byte[]> targetResponseData;
    final HttpRequestTransformationStatus transformationStatus;
    final Throwable errorCause;
    Duration targetResponseDuration;

    public SourceTargetCaptureTuple(@NonNull UniqueSourceRequestKey uniqueRequestKey,
                                    RequestResponsePacketPair sourcePair,
                                    TransformedPackets targetRequestData,
                                    List<byte[]> targetResponseData,
                                    HttpRequestTransformationStatus transformationStatus,
                                    Throwable errorCause,
                                    Duration targetResponseDuration) {
        this.uniqueRequestKey = uniqueRequestKey;
        this.sourcePair = sourcePair;
        this.targetRequestData = targetRequestData;
        this.targetResponseData = targetResponseData;
        this.transformationStatus = transformationStatus;
        this.errorCause = errorCause;
        this.targetResponseDuration = targetResponseDuration;
    }

    @Override
    public void close() {
        Optional.ofNullable(targetRequestData).ifPresent(d->d.close());
    }

    public static class TupleToStreamConsumer implements Consumer<SourceTargetCaptureTuple> {
        Logger tupleLogger = LoggerFactory.getLogger(OUTPUT_TUPLE_JSON_LOGGER);

        private static Stream<ByteBuf> byteToByteBufStream(List<byte[]> incoming) {
            return incoming.stream().map(b->Unpooled.wrappedBuffer(b));
        }

        private static Map<String,Object> fillMap(LinkedHashMap<String,Object> map,
                                                  HttpHeaders headers, ByteBuf content) {
            String base64body = Base64.getEncoder().encodeToString(content.array());
            content.release();
            map.put("body", base64body);
            headers.entries().stream().forEach(kvp->map.put(kvp.getKey(), kvp.getValue()));
            return map;
        }

        private Map<String, Object> makeSafeMap(Callable<Map<String,Object>> c) {
            try {
                return c.call();
            } catch (Exception e) {
                log.warn("Putting what may be a bogus value in the output because transforming it " +
                        "into json threw an exception");
                return Map.of("Exception", (Object) e.toString());
            }
        }

        private Map<String,Object> convertRequest(@NonNull List<byte[]> data)  {
            return makeSafeMap(()->{
                var map = new LinkedHashMap<String, Object>();
                var message = HttpByteBufFormatter.parseHttpRequestFromBufs(byteToByteBufStream(data), true);
                map.put("Request-URI", message.uri().toString());
                map.put("Method", message.method().toString());
                map.put("HTTP-Version", message.protocolVersion().toString());
                return fillMap(map, message.headers(), message.content());
            });
        }

        private Map<String,Object> convertResponse(@NonNull List<byte[]> data, Duration latency)  {
            return makeSafeMap(()-> {
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

        private Map<String,Object> toJSONObject(SourceTargetCaptureTuple tuple) {
            var tupleMap = new LinkedHashMap<String,Object>();
            Optional.ofNullable(tuple.sourcePair).ifPresent(p-> {
                Optional.ofNullable(p.requestData).flatMap(d -> Optional.ofNullable(d.packetBytes))
                        .ifPresent(d -> tupleMap.put("sourceRequest", convertRequest(d)));
                Optional.ofNullable(p.responseData).flatMap(d -> Optional.ofNullable(d.packetBytes))
                        .ifPresent(d -> tupleMap.put("sourceResponse", convertResponse(d,
                                // TODO: These durations are not measuring the same values!
                                Duration.between(tuple.sourcePair.requestData.getLastPacketTimestamp(),
                                        tuple.sourcePair.responseData.getLastPacketTimestamp()))));
            });

            Optional.ofNullable(tuple.targetRequestData)
                    .map(d->d.asByteArrayStream())
                    .ifPresent(d->tupleMap.put("targetRequest", convertRequest(d.collect(Collectors.toList()))));

            Optional.ofNullable(tuple.targetResponseData)
                    .filter(r->!r.isEmpty())
                    .ifPresent(d-> tupleMap.put("targetResponse", convertResponse(d, tuple.targetResponseDuration)));
            tupleMap.put("connectionId", formatUniqueRequestKey(tuple.uniqueRequestKey));
            Optional.ofNullable(tuple.errorCause).ifPresent(e->tupleMap.put("error", e.toString()));
            return tupleMap;
        }

        /**
         * Writes a tuple object to an output stream as a JSON object.
         * The JSON tuple is output on one line, and has several objects: "sourceRequest", "sourceResponse",
         * "targetRequest", and "targetResponse". The "connectionId" is also included to aid in debugging.
         * An example of the format is below.
         * <p>
         * {
         *   "sourceRequest": {
         *     "Request-URI": XYZ,
         *     "Method": XYZ,
         *     "HTTP-Version": XYZ
         *     "body": XYZ,
         *     "header-1": XYZ,
         *     "header-2": XYZ
         *   },
         *   "targetRequest": {
         *     "Request-URI": XYZ,
         *     "Method": XYZ,
         *     "HTTP-Version": XYZ
         *     "body": XYZ,
         *     "header-1": XYZ,
         *     "header-2": XYZ
         *   },
         *   "sourceResponse": {
         *     "HTTP-Version": ABC,
         *     "Status-Code": ABC,
         *     "Reason-Phrase": ABC,
         *     "response_time_ms": 123,
         *     "body": ABC,
         *     "header-1": ABC
         *   },
         *   "targetResponse": {
         *     "HTTP-Version": ABC,
         *     "Status-Code": ABC,
         *     "Reason-Phrase": ABC,
         *     "response_time_ms": 123,
         *     "body": ABC,
         *     "header-2": ABC
         *   },
         *   "connectionId": "0242acfffe1d0008-0000000c-00000003-0745a19f7c3c5fc9-121001ff.0"
         * }
         *
         * @param  tuple  the RequestResponseResponseTriple object to be converted into json and written to the stream.
         */
        @Override
        @SneakyThrows
        public void accept(SourceTargetCaptureTuple tuple) {
            tupleLogger.atInfo().setMessage(()-> {
                try {
                    return PLAIN_MAPPER.writeValueAsString(toJSONObject(tuple));
                } catch (Exception e) {
                    throw Lombok.sneakyThrow(e);
                }
            }).log();
        }
    }

    @Override
    public String toString() {
        return HttpByteBufFormatter.setPrintStyleFor(HttpByteBufFormatter.PacketPrintFormat.TRUNCATED, () -> {
            final StringJoiner sj = new StringJoiner("\n ", "SourceTargetCaptureTuple{","}");
            sj.add("diagnosticLabel=").add(uniqueRequestKey.toString());
            if (sourcePair != null) { sj.add("sourcePair=").add(sourcePair.toString()); }
            if (targetResponseDuration != null) { sj.add("targetResponseDuration=").add(targetResponseDuration+""); }
            Optional.ofNullable(targetRequestData).ifPresent(d-> sj.add("targetRequestData=")
                    .add(d.isClosed() ? "CLOSED" : HttpByteBufFormatter.httpPacketBufsToString(
                            HttpByteBufFormatter.HttpMessageType.REQUEST, d.streamUnretained(), false)));
            Optional.ofNullable(targetResponseData).filter(d->!d.isEmpty()).ifPresent(d -> sj.add("targetResponseData=")
                    .add(HttpByteBufFormatter.httpPacketBytesToString(HttpByteBufFormatter.HttpMessageType.RESPONSE, d)));
            sj.add("transformStatus=").add(transformationStatus+"");
            sj.add("errorCause=").add(errorCause == null ? "none" : errorCause.toString());
            return sj.toString();
        });
    }
}
