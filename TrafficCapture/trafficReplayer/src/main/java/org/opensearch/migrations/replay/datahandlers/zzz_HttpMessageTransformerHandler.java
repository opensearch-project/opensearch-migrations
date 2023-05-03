//package org.opensearch.migrations.replay.datahandlers;
//
//import com.fasterxml.jackson.databind.ObjectMapper;
//import org.opensearch.migrations.replay.datahandlers.http.HttpMessageTransformer;
//import org.opensearch.migrations.transform.JsonTransformer;
//
//public class HttpMessageTransformerHandler extends PacketToTransformingHttpMessageHandler {
//    public HttpMessageTransformerHandler(JsonTransformer jsonTransformer, IPacketToHttpHandler httpHandler) {
//        super(httpHandler, new HttpMessageTransformer(jsonTransformer));
//    }
//}
