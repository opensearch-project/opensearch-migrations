package org.opensearch.migrations.replay;

import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.HTTP;
import org.json.JSONObject;
import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.datatypes.TransformedPackets;
import org.opensearch.migrations.replay.datatypes.UniqueSourceRequestKey;

import java.io.IOException;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import java.util.StringJoiner;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Slf4j
public class SourceTargetCaptureTuple implements AutoCloseable {
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
        OutputStream outputStream;
        Logger tupleLogger = LogManager.getLogger("OutputTupleJsonLogger");

        public TupleToStreamConsumer(OutputStream outputStream){
            this.outputStream = outputStream;
        }

        private JSONObject jsonFromHttpDataUnsafe(List<byte[]> data) throws IOException {
            SequenceInputStream collatedStream = ReplayUtils.byteArraysToInputStream(data);
            Scanner scanner = new Scanner(collatedStream, StandardCharsets.UTF_8);
            scanner.useDelimiter("\r\n\r\n");  // The headers are seperated from the body with two newlines.
            String head = scanner.next();
            int headerLength = head.getBytes(StandardCharsets.UTF_8).length + 4; // The extra 4 bytes accounts for the two newlines.
            // SequenceInputStreams cannot be reset, so it's recreated from the original data.
            SequenceInputStream bodyStream = ReplayUtils.byteArraysToInputStream(data);
            for (int leftToSkip = headerLength; leftToSkip > 0; leftToSkip -= bodyStream.skip(leftToSkip)) {}

            // There are several limitations introduced by using the HTTP.toJSONObject call.
            // 1. We need to replace "\r\n" with "\n" which could mask differences in the responses.
            // 2. It puts all headers as top level keys in the JSON object, instead of e.g. inside a "header" object.
            //    We deal with this in the code that reads these JSONs, but it's a more brittle and error-prone format
            //    than it would be otherwise.
            // TODO: Refactor how messages are converted to JSON and consider using a more sophisticated HTTP parsing strategy.
            JSONObject message = HTTP.toJSONObject(head.replace("\r\n", "\n"));
            String base64body = Base64.getEncoder().encodeToString(bodyStream.readAllBytes());
            message.put("body", base64body);
            return message;
        }

        private JSONObject jsonFromHttpData(@NonNull List<byte[]> data) {
            try {
                return jsonFromHttpDataUnsafe(data);
            } catch (Exception e) {
                log.warn("Putting what may be a bogus value in the output because transforming it " +
                        "into json threw an exception");
                return new JSONObject(Map.of("Exception", e.toString()));
            }
        }

        private JSONObject jsonFromHttpData(@NonNull List<byte[]> data, Duration latency) {
            JSONObject message = jsonFromHttpData(data);
            message.put("response_time_ms", latency.toMillis());
            return message;
        }

        private JSONObject toJSONObject(SourceTargetCaptureTuple tuple) {
            // TODO: Use Netty to parse the packets as HTTP rather than json.org (we can also remove it as a dependency)
            JSONObject meta = new JSONObject();
            Optional.ofNullable(tuple.sourcePair).ifPresent(p-> {
                Optional.ofNullable(p.requestData).flatMap(d -> Optional.ofNullable(d.packetBytes))
                        .ifPresent(d -> meta.put("sourceRequest", jsonFromHttpData(d)));
                Optional.ofNullable(p.responseData).flatMap(d -> Optional.ofNullable(d.packetBytes))
                        .ifPresent(d -> meta.put("sourceResponse", jsonFromHttpData(d,
                                // TODO: These durations are not measuring the same values!
                                Duration.between(tuple.sourcePair.requestData.getLastPacketTimestamp(),
                                        tuple.sourcePair.responseData.getLastPacketTimestamp()))));
            });

            Optional.ofNullable(tuple.targetRequestData)
                    .map(d->d.asByteArrayStream())
                    .ifPresent(d->meta.put("targetRequest", jsonFromHttpData(d.collect(Collectors.toList()))));

            Optional.ofNullable(tuple.targetResponseData)
                    .filter(r->!r.isEmpty())
                    .ifPresent(d-> meta.put("targetResponse", jsonFromHttpData(d, tuple.targetResponseDuration)));
            meta.put("connectionId", tuple.uniqueRequestKey);
            Optional.ofNullable(tuple.errorCause).ifPresent(e->meta.put("error", e));
            return meta;
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
         * @param  triple  the RequestResponseResponseTriple object to be converted into json and written to the stream.
         */
        @Override
        @SneakyThrows
        public void accept(SourceTargetCaptureTuple triple) {
            JSONObject jsonObject = toJSONObject(triple);

            tupleLogger.info(()->jsonObject.toString());
            outputStream.write((jsonObject.toString()+"\n").getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
        }
    }

    @Override
    public String toString() {
        return PrettyPrinter.setPrintStyleFor(PrettyPrinter.PacketPrintFormat.TRUNCATED, () -> {
            final StringJoiner sj = new StringJoiner("\n ", "SourceTargetCaptureTuple{","}");
            sj.add("diagnosticLabel=").add(uniqueRequestKey.toString());
            if (sourcePair != null) { sj.add("sourcePair=").add(sourcePair.toString()); }
            if (targetResponseDuration != null) { sj.add("targetResponseDuration=").add(targetResponseDuration+""); }
            Optional.ofNullable(targetRequestData).ifPresent(d-> sj.add("targetRequestData=")
                    .add(d.isClosed() ? "CLOSED" : PrettyPrinter.httpPacketBufsToString(
                            PrettyPrinter.HttpMessageType.REQUEST, d.streamUnretained())));
            Optional.ofNullable(targetResponseData).filter(d->!d.isEmpty()).ifPresent(d -> sj.add("targetResponseData=")
                    .add(PrettyPrinter.httpPacketBytesToString(PrettyPrinter.HttpMessageType.RESPONSE, d)));
            sj.add("transformStatus=").add(transformationStatus+"");
            sj.add("errorCause=").add(errorCause == null ? "none" : errorCause.toString());
            return sj.toString();
        });
    }
}
