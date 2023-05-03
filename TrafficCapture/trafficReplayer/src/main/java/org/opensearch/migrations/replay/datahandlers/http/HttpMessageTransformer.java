//package org.opensearch.migrations.replay.datahandlers.http;
//
//import io.netty.buffer.ByteBuf;
//import io.netty.buffer.ByteBufOutputStream;
//import lombok.extern.slf4j.Slf4j;
//import org.opensearch.migrations.replay.AggregatedRawResponse;
//import org.opensearch.migrations.replay.datahandlers.IPacketToHttpHandler;
//import org.opensearch.migrations.transform.JsonTransformer;
//
//import java.util.ArrayList;
//import java.util.concurrent.CompletableFuture;
//
//@Slf4j
//public class HttpMessageTransformer implements IPacketToHttpHandler {
//    private final HttpJsonTransformer httpJsonTransformer;
//
//    public HttpMessageTransformer(JsonTransformer jsonTransformer, IPacketToHttpHandler transformedPacketReceiver) {
//        this.httpJsonTransformer = new HttpJsonTransformer(jsonTransformer, transformedPacketReceiver);
//    }
//
//    @Override
//    public CompletableFuture<Void> consumeBytes(ByteBuf nextRequestPacket) {
//        httpJsonTransformer.acceptRawRequestBytes(nextRequestPacket);
//        return CompletableFuture.completedFuture(null);
//    }
//
//    @Override
//    public CompletableFuture<AggregatedRawResponse> finalizeRequest() {
//        log.error("This needs to be cleaned up - there could be partial requests or things in flight that could gum things up");
//        return httpJsonTransformer.getTransformedPacketReceiver().finalizeRequest();
//    }
//
//    private ByteBuf toHttpStream(HttpJsonMessageWithFaultablePayload asJsonDoc) throws IOException {
//
//    }
//
////    private HttpJsonMessageWithLazyPayload toJson(HttpJsonTransformer httpParser) {
////        return httpParser.asJsonDocument();
////    }
//}
