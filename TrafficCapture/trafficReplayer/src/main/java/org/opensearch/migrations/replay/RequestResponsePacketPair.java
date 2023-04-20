package org.opensearch.migrations.replay;

import lombok.extern.slf4j.Slf4j;

import java.nio.charset.Charset;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.stream.Stream;

@Slf4j
public class RequestResponsePacketPair {
    Instant firstTimeStampForRequest;
    Instant lastTimeStampForRequest;
    Instant firstTimeStampForResponse;
    Instant lastTimeStampForResponse;

    final ArrayList<byte[]> requestData;
    final ArrayList<byte[]> responseData;

    public Duration getTotalRequestDuration() {
        return Duration.between(firstTimeStampForRequest, lastTimeStampForRequest);
    }

    public Duration getTotalResponseDuration() {
        return Duration.between(firstTimeStampForResponse, lastTimeStampForResponse);
    }

    public RequestResponsePacketPair() {
        this.requestData = new ArrayList<>();
        this.responseData = new ArrayList<>();
    }

    public void addRequestData(Instant packetTimeStamp, byte[] data) {
        if (log.isTraceEnabled()) {
            log.trace(this + " Adding request data: " + new String(data, Charset.defaultCharset()));
        }
        requestData.add(data);
        if (firstTimeStampForRequest == null) {
            firstTimeStampForRequest = packetTimeStamp;
        }
        lastTimeStampForRequest = packetTimeStamp;
    }

    public void addResponseData(Instant packetTimeStamp, byte[] data) {
        if (log.isTraceEnabled()) {
            log.trace(this + " Adding response data (len=" + responseData.size() + "): " +
                    new String(data, Charset.defaultCharset()));
        }
        responseData.add(data);
        lastTimeStampForResponse = packetTimeStamp;
    }

    public Stream<byte[]> getRequestDataStream() {
        return requestData.stream();
    }

    public Stream<byte[]> getResponseDataStream() {
        return responseData.stream();
    }
}
