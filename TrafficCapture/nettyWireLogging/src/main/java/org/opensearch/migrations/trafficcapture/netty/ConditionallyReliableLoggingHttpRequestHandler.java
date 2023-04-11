package org.opensearch.migrations.trafficcapture.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpHeaders;
import org.opensearch.migrations.trafficcapture.IChannelConnectionCaptureSerializer;

import java.io.IOException;
import java.util.List;
import java.util.function.Predicate;

public class ConditionallyReliableLoggingHttpRequestHandler extends LoggingHttpRequestHandler {
    private final Predicate<HttpHeaders> shouldBlockPredicate;

    public ConditionallyReliableLoggingHttpRequestHandler(IChannelConnectionCaptureSerializer trafficOffloader,
                                                          Predicate<HttpHeaders> headerPredicateForWhenToBlock) {
        super(trafficOffloader);
        this.shouldBlockPredicate = headerPredicateForWhenToBlock;
    }

    @Override
    protected void onHttpObjectsDecoded(List<Object> parsedObjects) throws IOException {
        super.onHttpObjectsDecoded(parsedObjects);
    }
}
