package org.opensearch.migrations.trafficcapture.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpResponseDecoder;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.opensearch.migrations.trafficcapture.IChannelConnectionCaptureSerializer;

import java.io.IOException;
import java.net.SocketAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class LoggingHttpResponseHandler extends ChannelOutboundHandlerAdapter {
    public static class SimpleHttpResponseDecoder extends HttpResponseDecoder {
        /**
         * Override to broaden the visibility.
         * @param ctx           the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
         * @param buffer            the {@link ByteBuf} from which to read data
         * @param out           the {@link List} to which decoded messages should be added
         * @throws Exception
         */
        @Override
        public void decode(ChannelHandlerContext ctx, ByteBuf buffer, List<Object> out) throws Exception {
            super.decode(ctx, buffer, out);
        }

        @Override
        public void decodeLast(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
            super.decodeLast(ctx, in, out);
        }

        @Override
        public HttpMessage createMessage(String[] initialLine) {
            return new DefaultHttpResponse(
                    HttpVersion.valueOf(initialLine[0]),
                    HttpResponseStatus.valueOf(Integer.parseInt(initialLine[1]), initialLine[2])
                    , new PassThruHttpHeaders()
            );
        }
    }

    private final IChannelConnectionCaptureSerializer trafficOffloader;
    private final SimpleHttpResponseDecoder decoder;
    public LoggingHttpResponseHandler(IChannelConnectionCaptureSerializer trafficOffloader) {
        this.trafficOffloader = trafficOffloader;
        decoder = new SimpleHttpResponseDecoder();
    }

    public HttpCaptureSerializerUtil.HttpProcessedState onHttpObjectsDecoded(List<Object> parsedMsgs) throws IOException {
        return HttpCaptureSerializerUtil.addHttpMessageIndicatorEvents(trafficOffloader, parsedMsgs);
    }

    public void parseHttpMessageParts(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        var bb = (ByteBuf) msg;
        bb.markReaderIndex();
        // todo - move this into a pool
        var parsedMsgs = new ArrayList<>(4);
        decoder.decode(ctx, bb, parsedMsgs);
        bb.resetReaderIndex();
        var httpProcessedState = onHttpObjectsDecoded(parsedMsgs);
        if (httpProcessedState == HttpCaptureSerializerUtil.HttpProcessedState.FULL_MESSAGE) {
            trafficOffloader.flushCommitAndResetStream(false);
        }
    }

    @Override
    public void bind(ChannelHandlerContext ctx, SocketAddress localAddress, ChannelPromise promise) throws Exception {
        trafficOffloader.addBindEvent(Instant.now(), localAddress);
        super.bind(ctx, localAddress, promise);
    }

    @Override
    public void connect(ChannelHandlerContext ctx, SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) throws Exception {
        trafficOffloader.addConnectEvent(Instant.now(), remoteAddress, localAddress);
        super.connect(ctx, remoteAddress, localAddress, promise);
    }

    @Override
    public void disconnect(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        trafficOffloader.addDisconnectEvent(Instant.now());
        super.disconnect(ctx, promise);
    }

    @Override
    public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        trafficOffloader.addCloseEvent(Instant.now());
        super.close(ctx, promise);
    }

    @Override
    public void deregister(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        trafficOffloader.addDeregisterEvent(Instant.now());
        super.deregister(ctx, promise);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        trafficOffloader.addWriteEvent(Instant.now(), (ByteBuf) msg);
        parseHttpMessageParts(ctx, msg, promise);
        super.write(ctx, msg, promise);
    }

    @Override
    public void flush(ChannelHandlerContext ctx) throws Exception {
        super.flush(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        trafficOffloader.addExceptionCaughtEvent(Instant.now(), cause);
        super.exceptionCaught(ctx, cause);
    }

}
