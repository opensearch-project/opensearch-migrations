package org.opensearch.migrations.trafficcapture;

import io.netty.buffer.ByteBuf;

import java.io.IOException;
import java.net.SocketAddress;

public interface IChannelConnectionCaptureOffloader {
    default void addBindEvent(SocketAddress addr) throws IOException {}
    default void addConnectEvent(SocketAddress remote, SocketAddress local) throws IOException {}
    default void addDisconnectEvent() throws IOException {}
    default void addCloseEvent() throws IOException {}
    default void addDeregisterEvent() throws IOException {}
    default void addReadEvent(ByteBuf buffer) throws IOException {}
    default void addWriteEvent(ByteBuf buffer) {}
    default void addFlushEvent() {}
    default void addChannelRegisteredEvent() {}
    default void addChannelUnregisteredEvent() {}
    default void addChannelActiveEvent() {}
    default void addChannelInactiveEvent() {}

    default void addChannelReadEvent() {}
    default void addChannelReadCompleteEvent() {}
    default void addUserEventTriggeredEvent() {}
    default void addChannelWritabilityChangedEvent() {}
    default void addExceptionCaughtEvent(Throwable t) throws IOException {}
}
