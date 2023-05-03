package org.opensearch.migrations.replay.datahandlers.http;

import lombok.AllArgsConstructor;

import java.nio.ByteBuffer;

@AllArgsConstructor
class SerializationCursor {
    private enum Section {
        HEADERS, PAYLOAD
    }

    public final Section section;
    public final ByteBuffer remaining;


}
