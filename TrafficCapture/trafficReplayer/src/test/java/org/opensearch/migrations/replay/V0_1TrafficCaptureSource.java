package org.opensearch.migrations.replay;

import org.opensearch.migrations.replay.traffic.source.ITrafficStreamWithKey;
import org.opensearch.migrations.replay.traffic.source.InputStreamOfTraffic;
import org.opensearch.migrations.replay.traffic.source.TrafficStreamWithEmbeddedKey;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

public class V0_1TrafficCaptureSource extends CompressedFileTrafficCaptureSource {

    protected final HashMap<String, Progress> connectionProgressMap;

    public V0_1TrafficCaptureSource(String filename) throws IOException {
        super(filename);
        connectionProgressMap = new HashMap<>();
    }

    @Override
    protected ITrafficStreamWithKey modifyTrafficStream(ITrafficStreamWithKey streamWithKey) {
        var incoming = streamWithKey.getStream();
        var outgoingBuilder = TrafficStream.newBuilder()
                .setNodeId(incoming.getNodeId())
                .setConnectionId(incoming.getConnectionId());
        if (incoming.hasNumber()) {
            outgoingBuilder.setNumber(incoming.getNumber());
        } else {
            outgoingBuilder.setNumberOfThisLastChunk(incoming.getNumberOfThisLastChunk());
        }
        var progress = connectionProgressMap.get(incoming.getConnectionId());
        if (progress == null) {
            progress = new Progress();
            progress.lastWasRead = streamWithKey.getKey().getTrafficStreamIndex() != 0;
            connectionProgressMap.put(incoming.getConnectionId(), progress);
        }
        outgoingBuilder.setLastObservationWasUnterminatedRead(progress.lastWasRead);
        outgoingBuilder.setPriorRequestsReceived(progress.requestCount);
        outgoingBuilder.addAllSubStream(incoming.getSubStreamList());
        progress.add(incoming);
        if (incoming.hasNumberOfThisLastChunk()) {
            connectionProgressMap.remove(incoming.getConnectionId());
        }
        return new TrafficStreamWithEmbeddedKey(outgoingBuilder.build());
    }

    private static class Progress {
        boolean lastWasRead;
        int requestCount;

        public void add(TrafficStream incoming) {
            var list = incoming.getSubStreamList();
            lastWasRead = list.isEmpty() ? lastWasRead :
                    Optional.of(list.get(list.size() - 1)).map(tso -> tso.hasRead() || tso.hasReadSegment()).get();
            requestCount += list.stream().filter(tso -> tso.hasRead() || tso.hasReadSegment()).count();
        }
    }
}
