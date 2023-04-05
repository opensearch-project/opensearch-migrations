package org.opensearch.migrations.replay;

import com.google.common.primitives.Bytes;
import lombok.extern.log4j.Log4j2;
import org.json.HTTP;
import org.json.JSONObject;
import org.opensearch.migrations.replay.TrafficReplayer.RequestResponsePacketPair;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

@Log4j2
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

        private JSONObject jsonFromHttpData(List<byte[]> data) {
            String aggregatedString = new String(Bytes.concat(data.toArray(byte[][]::new)), Charset.defaultCharset());
            // There are several limitations introduced by using the HTTP.toJSONObject call.
            // 1. We need to replace "\r\n" with "\n" which could mask differences in the responses.
            // 2. It puts all headers as top level keys in the JSON object, instead of e.g. inside a "header" object.
            //    We deal with this in the code that reads these JSONs, but it's a more brittle and error-prone format
            //    than it would be otherwise.
            // TODO: Refactor how messages are converted to JSON and consider using a more sophisticated HTTP parsing strategy.
            String[] splitMessage = aggregatedString.split("\r\n\r\n", 2);
            JSONObject message = HTTP.toJSONObject(splitMessage[0].replaceAll("\r\n", "\n"));
            if (splitMessage.length > 1) {
                message.put("body", splitMessage[1]);
            } else {
                message.put("body", "");
            }
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
            outputStream.flush();
        }
    }
}
