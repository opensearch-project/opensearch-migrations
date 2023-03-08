package org.opensearch.migrations.replay;

import com.google.common.primitives.Bytes;
import org.json.HTTP;
import org.json.JSONObject;
import org.opensearch.migrations.replay.TrafficReplayer.RequestResponsePacketPair;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public class RequestResponseResponseTriple<write> {
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

    private JSONObject jsonFromHttpData(List<byte[]> data, Duration duration) {
        JSONObject message = jsonFromHttpData(data);
        message.put("response_time_ms", duration.toMillis());
        return message;
    }

    private JSONObject toJSONObject() {
        JSONObject meta = new JSONObject();
        meta.put("request", jsonFromHttpData(sourcePair.requestData));
        meta.put("primaryResponse", jsonFromHttpData(sourcePair.responseData, sourcePair.getTotalDuration()));
        meta.put("shadowResponse", jsonFromHttpData(shadowResponseData, shadowResponseDuration));

        return meta;
    }

    public static class TripleToFileWriter implements AutoCloseable {
        FileOutputStream fileOutputStream;
        BufferedOutputStream bufferedOutputStream;

        public TripleToFileWriter(Path path) throws IOException {
            fileOutputStream = new FileOutputStream(path.toFile(), true);
            bufferedOutputStream = new BufferedOutputStream(fileOutputStream);
        }

        public void writeJSON(RequestResponseResponseTriple triple) throws IOException {
            JSONObject jsonObject = triple.toJSONObject();

            bufferedOutputStream.write(jsonObject.toString().getBytes(StandardCharsets.UTF_8));
            bufferedOutputStream.write("\n".getBytes(StandardCharsets.UTF_8));
        }

        public void close() throws IOException {
            bufferedOutputStream.close();
            fileOutputStream.close();
        }
    }
}
