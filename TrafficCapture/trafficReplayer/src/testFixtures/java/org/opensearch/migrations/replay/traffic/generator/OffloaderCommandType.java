package org.opensearch.migrations.replay.traffic.generator;

public enum OffloaderCommandType {
    Read,
    EndOfMessage,
    DropRequest,
    Write,
    Flush
}
