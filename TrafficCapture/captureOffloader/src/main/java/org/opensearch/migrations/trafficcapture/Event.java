package org.opensearch.migrations.trafficcapture;

import java.util.HashMap;
import java.util.Map;

public enum Event {
    Bind('b'),
    Connect('c'),
    Disconnect('d'),
    Close('t'),
    ChannelRegistered('r'),
    Deregister('d'),
    Read('R'),
    Write('W'),
    Flush('F');

    private static final Map<String, Event> kLabelMap = new HashMap<>();

    private final char key;

    Event(char i) {
        this.key = i;
    }

}
