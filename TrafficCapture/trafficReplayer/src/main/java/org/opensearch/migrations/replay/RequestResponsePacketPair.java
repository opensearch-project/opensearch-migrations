package org.opensearch.migrations.replay;

import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.datatypes.UniqueRequestKey;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

@Slf4j
public class RequestResponsePacketPair {

    HttpMessageAndTimestamp requestData;
    HttpMessageAndTimestamp responseData;
    public final UniqueRequestKey connectionId;

    public RequestResponsePacketPair(UniqueRequestKey connectionId) {
        this.connectionId = connectionId;
    }

    public void addRequestData(Instant packetTimeStamp, byte[] data) {
        if (log.isTraceEnabled()) {
            log.trace(this + " Adding request data: " + new String(data, StandardCharsets.UTF_8));
        }
        if (requestData == null) {
            requestData = new HttpMessageAndTimestamp.Request(packetTimeStamp);
        }
        requestData.add(data);
        requestData.setLastPacketTimestamp(packetTimeStamp);
    }

    public void addResponseData(Instant packetTimeStamp, byte[] data) {
        if (log.isTraceEnabled()) {
            log.trace(this + " Adding response data: " + new String(data, StandardCharsets.UTF_8));
        }
        if (responseData == null) {
            responseData = new HttpMessageAndTimestamp.Response(packetTimeStamp);
        }
        responseData.add(data);
        responseData.setLastPacketTimestamp(packetTimeStamp);
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("RequestResponsePacketPair{");
        sb.append("\n requestData=").append(requestData);
        sb.append("\n responseData=").append(responseData);
        sb.append('}');
        return sb.toString();
    }

    public Optional<Instant> getLastTimestamp() {
        return Optional.ofNullable(responseData)
                .or(()->Optional.ofNullable(requestData))
                .map(d->d.getLastPacketTimestamp());
    }
}
