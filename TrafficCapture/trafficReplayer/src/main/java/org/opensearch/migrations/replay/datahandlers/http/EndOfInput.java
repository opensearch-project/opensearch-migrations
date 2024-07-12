package org.opensearch.migrations.replay.datahandlers.http;

/**
 * This is a sentinel value that is used like LastHttpContent but is relevant for pipelines
 * that are not parsing HTTP contents.  This is sent immediately before the EmbeddedChannel
 * is closed.  It allows the NettySendByteBufsToPacketHandlerHandler class to determine
 * whether all the contents were received or if there was an error in-flight.
 */
public class EndOfInput {}
