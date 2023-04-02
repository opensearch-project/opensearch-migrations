package org.opensearch.migrations.trafficcapture.proxyserver.netty;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.extern.slf4j.Slf4j;


@Slf4j
public class BacksideHandler extends ChannelInboundHandlerAdapter {

    private final Channel writeBackChannel;

    public BacksideHandler(Channel writeBackChannel) {
        this.writeBackChannel = writeBackChannel;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        ctx.read();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        writeBackChannel.writeAndFlush(msg);
    }

//    @Override
//    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
//        try (var baos = new ByteArrayOutputStream()) {
//            try (var responseWriter = new OutputStreamWriter(baos)) {
//                responseWriter.write("response");
//                responseWriter.flush();
//                var bb = Unpooled.wrappedBuffer(baos.toByteArray());
//                writeBackChannel.writeAndFlush(bb)
//                        .addListener((ChannelFutureListener)future -> {
//                            if (!future.isSuccess()) {
//                                log.debug("Failed writeback: "+future.cause());
//                                future.channel().close();
//                            }
//                });
//                log.debug("Wrote data to writeback channel");
//            }
//        }
//        super.channelReadComplete(ctx);
//    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.debug("inactive channel - closing");
        FrontsideHandler.closeAndFlush(writeBackChannel);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        FrontsideHandler.closeAndFlush(ctx.channel());
    }
}