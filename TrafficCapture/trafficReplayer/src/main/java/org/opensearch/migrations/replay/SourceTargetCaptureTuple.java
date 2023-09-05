package org.opensearch.migrations.replay;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.HTTP;
import org.json.JSONObject;
import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.datatypes.TransformedPackets;

import java.io.IOException;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.stream.Collectors;

@Slf4j
public class SourceTargetCaptureTuple implements AutoCloseable {
    private RequestResponsePacketPair sourcePair;
    private final TransformedPackets targetRequestData;
    private final List<byte[]> targetResponseData;
    private final HttpRequestTransformationStatus transformationStatus;
    private final Throwable errorCause;
    Duration targetResponseDuration;

    public SourceTargetCaptureTuple(RequestResponsePacketPair sourcePair,
                                    TransformedPackets targetRequestData,
                                    List<byte[]> targetResponseData,
                                    HttpRequestTransformationStatus transformationStatus,
                                    Throwable errorCause,
                                    Duration targetResponseDuration) {
        this.sourcePair = sourcePair;
        this.targetRequestData = targetRequestData;
        this.targetResponseData = targetResponseData;
        this.transformationStatus = transformationStatus;
        this.errorCause = errorCause;
        this.targetResponseDuration = targetResponseDuration;
    }

    @Override
    public void close() {
        targetRequestData.close();
    }

    public static class TupleToFileWriter {
        OutputStream outputStream;
        Logger tupleLogger = LogManager.getLogger("OutputTupleJsonLogger");

        public TupleToFileWriter(OutputStream outputStream){
            this.outputStream = outputStream;
        }

        private JSONObject jsonFromHttpDataUnsafe(List<byte[]> data) throws IOException {
            SequenceInputStream collatedStream = ReplayUtils.byteArraysToInputStream(data);
            Scanner scanner = new Scanner(collatedStream, StandardCharsets.UTF_8);
            scanner.useDelimiter("\r\n\r\n");  // The headers are seperated from the body with two newlines.
            String head = scanner.next();
            int header_length = head.getBytes(StandardCharsets.UTF_8).length + 4; // The extra 4 bytes accounts for the two newlines.
            // SequenceInputStreams cannot be reset, so it's recreated from the original data.
            SequenceInputStream bodyStream = ReplayUtils.byteArraysToInputStream(data);
            bodyStream.skip(header_length);

            // There are several limitations introduced by using the HTTP.toJSONObject call.
            // 1. We need to replace "\r\n" with "\n" which could mask differences in the responses.
            // 2. It puts all headers as top level keys in the JSON object, instead of e.g. inside a "header" object.
            //    We deal with this in the code that reads these JSONs, but it's a more brittle and error-prone format
            //    than it would be otherwise.
            // TODO: Refactor how messages are converted to JSON and consider using a more sophisticated HTTP parsing strategy.
            JSONObject message = HTTP.toJSONObject(head.replaceAll("\r\n", "\n"));
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

        private JSONObject toJSONObject(SourceTargetCaptureTuple triple) throws IOException {
            // TODO: Use Netty to parse the packets as HTTP rather than json.org (we can also remove it as a dependency)
            JSONObject meta = new JSONObject();
            meta.put("sourceRequest", jsonFromHttpData(triple.sourcePair.requestData.packetBytes));
            meta.put("targetRequest", jsonFromHttpData(triple.targetRequestData.asByteArrayStream()
                    .collect(Collectors.toList())));
            //log.warn("TODO: These durations are not measuring the same values!");
            if (triple.sourcePair.responseData != null) {
                meta.put("sourceResponse", jsonFromHttpData(triple.sourcePair.responseData.packetBytes,
                        Duration.between(triple.sourcePair.requestData.getLastPacketTimestamp(),
                                triple.sourcePair.responseData.getLastPacketTimestamp())));
            }
            if (triple.targetResponseData != null) {
                meta.put("targetResponse", jsonFromHttpData(triple.targetResponseData, triple.targetResponseDuration));
            }
            meta.put("connectionId", triple.sourcePair.connectionId);
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
        public void writeJSON(SourceTargetCaptureTuple triple) throws IOException {
            JSONObject jsonObject = toJSONObject(triple);

            tupleLogger.info(jsonObject.toString());
            outputStream.write((jsonObject.toString()+"\n").getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
        }
    }

    @Override
    public String toString() {
        return Utils.setPrintStyleFor(Utils.PacketPrintFormat.TRUNCATED, () -> {
            final StringBuilder sb = new StringBuilder("SourceTargetCaptureTuple{");
            sb.append("\n diagnosticLabel=").append(sourcePair.connectionId);
            sb.append("\n sourcePair=").append(sourcePair);
            sb.append("\n targetResponseDuration=").append(targetResponseDuration);
            sb.append("\n targetRequestData=").append(targetRequestData);
            sb.append("\n targetResponseData=").append(targetResponseData);
            sb.append("\n transformStatus=").append(transformationStatus);
            sb.append("\n errorCause=").append(errorCause == null ? "null" : errorCause.toString());
            sb.append('}');
            return sb.toString();
        });
    }
}
