package org.opensearch.migrations.replay;

import org.json.HTTP;
import org.json.JSONObject;
import org.opensearch.migrations.replay.TrafficReplayer.RequestResponsePacketPair;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Scanner;

public class RequestResponseResponseTriple {
    private RequestResponsePacketPair sourcePair;
    private List<byte[]> shadowResponseData;
    Duration shadowResponseDuration;

    public RequestResponseResponseTriple(RequestResponsePacketPair sourcePair,
                                         List<byte[]> shadowResponseData,
                                         Duration shadowResponseDuration) {
        this.sourcePair = sourcePair;
        this.shadowResponseData = shadowResponseData;
        this.shadowResponseDuration = shadowResponseDuration;
    }

    public static class TripleToFileWriter {
        OutputStream outputStream;

        private List<byte[]> extractBodyBytesAfterHeader(List<byte[]> data, int header_length) {
            // This method essentially iterates through data, discarding the first `header_length` bytes
            // and then returns the rest in the form of a new List<>, which is a sublist of the input List,
            // with a potentially modified first element (to discard header bytes).
            int currently_seen_length = 0;
            int index = 0;
            for (byte[] packet : data) {
                if (currently_seen_length + packet.length < header_length) {
                    currently_seen_length += packet.length;
                    index++;
                    continue;
                }
                if (currently_seen_length + packet.length == header_length) {
                    break;
                }
                byte[] new_first_array;
                if (header_length - currently_seen_length == packet.length) {
                    new_first_array = packet;
                } else {
                    new_first_array = Arrays.copyOfRange(packet, header_length - currently_seen_length, packet.length);
                }
                List<byte[]> newList = data.subList(++index, data.size());
                newList.add(0, new_first_array);
                return newList;
            }
            // If we make it out of this method, the header was the full data, return an empty list.
            return new ArrayList<byte[]>();
        }

        private JSONObject jsonFromHttpData(List<byte[]> data) throws IOException {

            SequenceInputStream collatedStream = new SequenceInputStream(Collections.enumeration(data.stream().map(b -> new ByteArrayInputStream(b)).toList()));
            Scanner scanner = new Scanner(collatedStream, StandardCharsets.UTF_8);
            scanner.useDelimiter("\r\n\r\n");  // The headers are seperated from the body with two newlines.
            String head = scanner.next();
            int header_length = head.getBytes(StandardCharsets.UTF_8).length + 4; // The extra 4 bytes accounts for the two newlines.
            List<byte[]> body = extractBodyBytesAfterHeader(data, header_length);
            SequenceInputStream bodyStream = new SequenceInputStream(Collections.enumeration(body.stream().map(b -> new ByteArrayInputStream(b)).toList()));

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

        private JSONObject jsonFromHttpData(List<byte[]> data, Duration latency) {
            JSONObject message = jsonFromHttpData(data);
            message.put("response_time_ms", latency.toMillis());
            return message;
        }

        private JSONObject toJSONObject(RequestResponseResponseTriple triple) {
            JSONObject meta = new JSONObject();
            meta.put("request", jsonFromHttpData(triple.sourcePair.requestData));
            meta.put("primaryResponse", jsonFromHttpData(triple.sourcePair.responseData, triple.sourcePair.getTotalDuration()));
            meta.put("shadowResponse", jsonFromHttpData(triple.shadowResponseData, triple.shadowResponseDuration));

            return meta;
        }

        public TripleToFileWriter(OutputStream outputStream){
            this.outputStream = outputStream;
        }

        /**
         * Writes a "triple" object to an output stream as a JSON object.
         * The JSON triple is output on one line, and has three objects: "request", "primaryResponse",
         * and "shadowResponse". An example of the format is below.
         * <p>
         * {
         *   "request": {
         *     "Request-URI": XYZ,
         *     "Method": XYZ,
         *     "HTTP-Version": XYZ
         *     "body": XYZ,
         *     "header-1": XYZ,
         *     "header-2": XYZ
         *   },
         *   "primaryResponse": {
         *     "HTTP-Version": ABC,
         *     "Status-Code": ABC,
         *     "Reason-Phrase": ABC,
         *     "response_time_ms": 123,
         *     "body": ABC,
         *     "header-1": ABC
         *   },
         *   "shadowResponse": {
         *     "HTTP-Version": ABC,
         *     "Status-Code": ABC,
         *     "Reason-Phrase": ABC,
         *     "response_time_ms": 123,
         *     "body": ABC,
         *     "header-2": ABC
         *   }
         * }
         *
         * @param  triple  the RequestResponseResponseTriple object to be converted into json and written to the stream.
         */
        public void writeJSON(RequestResponseResponseTriple triple) throws IOException {
            JSONObject jsonObject = toJSONObject(triple);

            outputStream.write(jsonObject.toString().getBytes(StandardCharsets.UTF_8));
            outputStream.write("\n".getBytes(StandardCharsets.UTF_8));
        }
    }
}
