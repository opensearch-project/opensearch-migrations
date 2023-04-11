package org.opensearch.migrations.trafficcapture.netty;

import org.opensearch.migrations.trafficcapture.IChannelConnectionCaptureSerializer;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

public class HttpCaptureSerializerUtil
{
    public static void addHttpMessageIndicatorEvents(IChannelConnectionCaptureSerializer trafficOffloader,
                                                      List<Object> parsedMsgs) throws IOException {
        Instant timestamp = Instant.now();
        for (var obj : parsedMsgs) {
            if (obj.equals(null)) {
                trafficOffloader.addEndOfHttpMessageIndicator(timestamp);
            } else if (obj.equals(null)) {
                trafficOffloader.addEndOfFirstLineIndicator(timestamp, -1);
                trafficOffloader.addEndOfHeadersIndicator(timestamp, -1);
            }
        }
    }
}
