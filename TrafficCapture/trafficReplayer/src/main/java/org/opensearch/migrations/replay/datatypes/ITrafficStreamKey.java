package org.opensearch.migrations.replay.datatypes;

public interface ITrafficStreamKey {
    String getNodeId();

    String getConnectionId();

    int getTrafficStreamIndex();
}
