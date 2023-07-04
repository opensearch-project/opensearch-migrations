package org.opensearch.migrations.replay;

import lombok.extern.slf4j.Slf4j;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.stream.Stream;

@Slf4j
public class RequestResponsePacketPair {

    HttpMessageAndTimestamp requestData;
    HttpMessageAndTimestamp responseData;
    public final String connectionId;

    public RequestResponsePacketPair(String connectionId) {
        this.connectionId = connectionId;
    }

    public void addRequestData(Instant packetTimeStamp, byte[] data) {
        if (log.isTraceEnabled()) {
            log.trace(this + " Adding request data: " + new String(data, StandardCharsets.UTF_8));
        }
        if (requestData == null) {
            requestData = new HttpMessageAndTimestamp(packetTimeStamp);
        }
        requestData.add(data);
        requestData.setLastPacketTimestamp(packetTimeStamp);
    }

    public void addResponseData(Instant packetTimeStamp, byte[] data) {
        if (log.isTraceEnabled()) {
            log.trace(this + " Adding response data: " + new String(data, StandardCharsets.UTF_8));
        }
        if (responseData == null) {
            responseData = new HttpMessageAndTimestamp(packetTimeStamp);
        }
        responseData.add(data);
        responseData.setLastPacketTimestamp(packetTimeStamp);
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("RequestResponsePacketPair{");
        sb.append("requestData=").append(requestData);
        sb.append(", responseData=").append(responseData);
        sb.append('}');
        return sb.toString();
    }
}
