package org.opensearch.migrations.replay.util;

import java.util.StringJoiner;

public class TrafficChannelKeyFormatter {
    private TrafficChannelKeyFormatter() {}

    public static String format(String nodeId, String connectionId) {
        return new StringJoiner(".").add(nodeId).add(connectionId).toString();
    }

    public static String format(String nodeId, String connectionId, int trafficStreamIndex) {
        return new StringJoiner(".").add(nodeId).add(connectionId).add("" + trafficStreamIndex).toString();
    }
}
