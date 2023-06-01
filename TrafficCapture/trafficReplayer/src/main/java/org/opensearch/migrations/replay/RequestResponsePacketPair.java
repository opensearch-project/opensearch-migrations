package org.opensearch.migrations.replay;

import lombok.extern.slf4j.Slf4j;

import java.nio.charset.Charset;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.stream.Stream;

@Slf4j
public class RequestResponsePacketPair {

    HttpMessageAndTimestamp requestData;
    HttpMessageAndTimestamp responseData;

    public void addRequestData(Instant packetTimeStamp, byte[] data) {
        if (log.isTraceEnabled()) {
            log.trace(this + " Adding request data: " + new String(data, Charset.defaultCharset()));
        }
        if (requestData == null) {
            requestData = new HttpMessageAndTimestamp(packetTimeStamp);
        }
        requestData.add(data);
        requestData.setLastPacketTimestamp(packetTimeStamp);
    }

    public void addResponseData(Instant packetTimeStamp, byte[] data) {
        if (log.isTraceEnabled()) {
            log.trace(this + " Adding response data: " + new String(data, Charset.defaultCharset()));
        }
        if (responseData == null) {
            responseData = new HttpMessageAndTimestamp(packetTimeStamp);
        }
        responseData.add(data);
        responseData.setLastPacketTimestamp(packetTimeStamp);
    }

    public Stream<byte[]> getRequestDataStream() {
        return requestData.stream();
    }

    public Stream<byte[]> getResponseDataStream() {
        return responseData.stream();
    }
}
