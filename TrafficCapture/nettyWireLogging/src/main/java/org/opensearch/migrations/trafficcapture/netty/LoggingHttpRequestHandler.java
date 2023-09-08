package org.opensearch.migrations.trafficcapture.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpMessageDecoderResult;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.trafficcapture.IChannelConnectionCaptureSerializer;
import org.opensearch.migrations.coreutils.MetricsLogger;

import java.time.Instant;

@Slf4j
public class LoggingHttpRequestHandler extends ChannelInboundHandlerAdapter {
    private static final MetricsLogger metricsLogger = new MetricsLogger("LoggingHttpRequestHandler");

    static class SimpleHttpRequestDecoder extends HttpRequestDecoder {
        /**
         * Override this so that the HttpHeaders object can be a cheaper one.  PassThruHeaders
         * only stores a handful of headers that are required for parsing the payload portion
         * of an HTTP Message.
         */
        @Override
        public HttpMessage createMessage(String[] initialLine) throws Exception {
            return new DefaultHttpRequest(HttpVersion.valueOf(initialLine[2]),
                    HttpMethod.valueOf(initialLine[0]), initialLine[1]
                    , new PassThruHttpHeaders()
            );
        }
    }

    static class SimpleDecodedHttpRequestHandler extends ChannelInboundHandlerAdapter {
        @Getter
        private HttpRequest currentRequest;
        boolean isDone;
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof HttpRequest) {
                currentRequest = (HttpRequest) msg;
            } else if (msg instanceof LastHttpContent) {
                isDone = true;
            }
            super.channelRead(ctx, msg);
        }

        public HttpRequest resetCurrentRequest() {
            isDone = false;
            var old = currentRequest;
            currentRequest = null;
            return old;
        }
    }

    protected final IChannelConnectionCaptureSerializer trafficOffloader;

    protected final EmbeddedChannel httpDecoderChannel;
    protected final SimpleHttpRequestDecoder requestDecoder;


    public LoggingHttpRequestHandler(IChannelConnectionCaptureSerializer trafficOffloader) {
        this.trafficOffloader = trafficOffloader;
        requestDecoder = new SimpleHttpRequestDecoder(); // as a field for easier debugging
        httpDecoderChannel = new EmbeddedChannel(
                requestDecoder,
                new SimpleDecodedHttpRequestHandler()
        );
    }

    private HttpCaptureSerializerUtil.HttpProcessedState parseHttpMessageParts(ByteBuf msg) throws Exception {
        var bb = msg;
        // Preserve ownership of this ByteBuf because the HttpRequestDecoder will take ownership of the
        // ByteBuf, releasing it once the data has been copied into its cumulation buffer.  However,
        // this handler still fires ByteBufs through the pipeline.  It's up to a future handler to perform
        // the release, as it would have been if this method was implemented any other way.
        bb.retain();
        bb.markReaderIndex();
        httpDecoderChannel.writeInbound(bb);
        bb.resetReaderIndex();
        return getHandlerThatHoldsParsedHttpRequest().isDone ?
                HttpCaptureSerializerUtil.HttpProcessedState.FULL_MESSAGE :
                HttpCaptureSerializerUtil.HttpProcessedState.ONGOING;
    }

    private SimpleDecodedHttpRequestHandler getHandlerThatHoldsParsedHttpRequest() {
        return (SimpleDecodedHttpRequestHandler) httpDecoderChannel.pipeline().last();
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        super.channelRegistered(ctx);
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        trafficOffloader.addCloseEvent(Instant.now());
        trafficOffloader.flushCommitAndResetStream(true).whenComplete((result, t) -> {
            if (t != null) {
                log.warn("Got error: " + t.getMessage());
                ctx.close();
            } else {
                try {
                    super.channelUnregistered(ctx);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
    }

    protected void channelFinishedReadingAnHttpMessage(ChannelHandlerContext ctx, Object msg, HttpRequest httpRequest) throws Exception {
        super.channelRead(ctx, msg);
        metricsLogger.atSuccess()
                .addKeyValue("channelId", ctx.channel().id().asLongText())
                .addKeyValue("httpMethod", httpRequest.method().toString())
                .addKeyValue("httpEndpoint", httpRequest.uri())
                .setMessage("Full request received").log();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        var timestamp = Instant.now();
        var bb = (ByteBuf) msg;
        trafficOffloader.addReadEvent(timestamp, bb);
        metricsLogger.atSuccess()
                .addKeyValue("channelId", ctx.channel().id().asLongText())
                .setMessage("Component of request received").log();

        var httpProcessedState = parseHttpMessageParts(bb);
        if (httpProcessedState == HttpCaptureSerializerUtil.HttpProcessedState.FULL_MESSAGE) {
            var httpRequest = getHandlerThatHoldsParsedHttpRequest().resetCurrentRequest();
            var decoderResultLoose = httpRequest.decoderResult();
            if (decoderResultLoose instanceof HttpMessageDecoderResult) {
                var decoderResult = (HttpMessageDecoderResult) decoderResultLoose;
                trafficOffloader.addEndOfFirstLineIndicator(decoderResult.initialLineLength());
                trafficOffloader.addEndOfHeadersIndicator(decoderResult.headerSize());
            }
            trafficOffloader.commitEndOfHttpMessageIndicator(timestamp);
            channelFinishedReadingAnHttpMessage(ctx, msg, httpRequest);
        } else {
            super.channelRead(ctx, msg);
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        super.channelReadComplete(ctx);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        super.userEventTriggered(ctx, evt);
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        super.channelWritabilityChanged(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        trafficOffloader.addExceptionCaughtEvent(Instant.now(), cause);
        httpDecoderChannel.close();
        super.exceptionCaught(ctx, cause);
    }

}
