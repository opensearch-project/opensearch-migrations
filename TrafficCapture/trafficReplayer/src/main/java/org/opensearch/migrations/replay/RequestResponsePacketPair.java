package org.opensearch.migrations.replay;

import com.google.common.base.Objects;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.UniqueRequestKey;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Slf4j
public class RequestResponsePacketPair {

    HttpMessageAndTimestamp requestData;
    HttpMessageAndTimestamp responseData;
    List<ITrafficStreamKey> trafficStreamKeysBeingHeld;
    public final UniqueRequestKey requestKey;

    public RequestResponsePacketPair(UniqueRequestKey requestKey) {
        this.requestKey = requestKey;
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

    public void holdTrafficStream(ITrafficStreamKey trafficStreamKey) {
        if (trafficStreamKeysBeingHeld == null) {
            trafficStreamKeysBeingHeld = new ArrayList<>();
        }
        trafficStreamKeysBeingHeld.add(trafficStreamKey);
    }

    private static final List<ITrafficStreamKey> emptyUnmodifiableList = List.of();
    public List<ITrafficStreamKey> getTrafficStreamsHeld() {
        return (trafficStreamKeysBeingHeld == null) ? emptyUnmodifiableList :
                Collections.unmodifiableList(trafficStreamKeysBeingHeld);
    }

    public Optional<Instant> getLastTimestamp() {
        return Optional.ofNullable(responseData)
                .or(()->Optional.ofNullable(requestData))
                .map(d->d.getLastPacketTimestamp());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RequestResponsePacketPair that = (RequestResponsePacketPair) o;
        return Objects.equal(requestData, that.requestData)
                && Objects.equal(responseData, that.responseData)
                && Objects.equal(requestKey, that.requestKey)
                && Objects.equal(trafficStreamKeysBeingHeld, that.trafficStreamKeysBeingHeld);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(requestData, responseData, requestKey, trafficStreamKeysBeingHeld);
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("RequestResponsePacketPair{");
        sb.append("\n requestData=").append(requestData);
        sb.append("\n responseData=").append(responseData);
        sb.append('}');
        return sb.toString();
    }

}
