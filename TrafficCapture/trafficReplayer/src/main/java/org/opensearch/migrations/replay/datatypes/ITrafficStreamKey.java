package org.opensearch.migrations.replay.datatypes;

public interface ITrafficStreamKey extends ISourceTrafficChannelKey {
    int getTrafficStreamIndex();
}
