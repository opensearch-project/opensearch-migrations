/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.transport;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.logging.ByteBufFormat;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Base64;

/**
 * Differs from netty's LoggingHandler by not dumping hex dumps but instead dumping a header
 * with length followed by the raw data.  Other netty connection information (ACTIVE, REGISTERED, etc)
 * are preserved for now.
 */
public class WireLoggingHandler extends LoggingHandler {

    private static final String CHARSET_NAME = "UTF-8";

    public WireLoggingHandler(String name, LogLevel level) {
        super(name, level, ByteBufFormat.SIMPLE /*doesn't matter*/);
    }

    /*
     * Formats an event and returns the formatted message.
     *
     * @param eventName the name of the event
     * @param arg       the argument of the event
     */
    protected String format(ChannelHandlerContext ctx, String eventName, Object arg) {
        if (arg instanceof ByteBuf) {
            return formatByteBuf(ctx, eventName, (ByteBuf) arg);
        } else if (arg instanceof ByteBufHolder) {
            return formatByteBuf(ctx, eventName, ((ByteBufHolder) arg).content());
        } else {
            return super.format(ctx, eventName, arg);
        }
    }

    private String formatByteBuf(ChannelHandlerContext ctx, String eventName, ByteBuf content) {
        try {
            String chStr = ctx.channel().toString();
            int contentLength = content.readableBytes();
            if (contentLength == 0) {
                StringBuilder buf = new StringBuilder(chStr.length() + 1 + eventName.length() + 3);
                buf.append(chStr).append(' ').append(eventName).append(" 0");
                return buf.toString();
            } else {
                int outputLength = chStr.length() + 1 + eventName.length() + 2 + 10 + 3 + contentLength * 2 + 1;
                StringBuilder buf = new StringBuilder(outputLength);
                buf.append(chStr).append(' ').append(eventName).append(' ')
                    .append(contentLength).append("\n");
                content.markReaderIndex();
                // this will be MUCH more efficient if we're able to write directly to an output stream
                // rather than going through all of the string/char trasnformations
                byte[] output = new byte[contentLength];
                content.readBytes(output);
                buf.append(Base64.getEncoder().encodeToString(output));
                content.resetReaderIndex();
                return buf.toString();
            }
        } catch (Exception e) {
            System.out.println("Exception: "+e.getMessage());
            e.printStackTrace();;
            return e.getMessage();
        }
    }
}
