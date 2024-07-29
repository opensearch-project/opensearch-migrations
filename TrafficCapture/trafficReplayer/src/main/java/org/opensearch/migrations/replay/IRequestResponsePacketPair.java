package org.opensearch.migrations.replay;
public interface IRequestResponsePacketPair {
    HttpMessageAndTimestamp getRequestData();
    HttpMessageAndTimestamp getResponseData();
}
