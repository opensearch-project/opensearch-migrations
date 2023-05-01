package org.opensearch.migrations.replay.datahandlers.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.opensearch.migrations.replay.datahandlers.ByteTransformer;
import org.opensearch.migrations.replay.datahandlers.PacketToTransformingHttpMessageHandler;
import org.opensearch.migrations.transform.JsonTransformer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class HttpMessageTransformer implements ByteTransformer {
    HttpParser httpParser = new HttpParser();
    JsonTransformer jsonTransformer;

    public HttpMessageTransformer(JsonTransformer jsonTransformer ) {
        this.jsonTransformer = jsonTransformer;
    }

    @Override
    public CompletableFuture<Void> addBytes(byte[] nextRequestPacket) {
        httpParser.write(nextRequestPacket);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<PacketToTransformingHttpMessageHandler.SizeAndInputStream> getFullyTransformedBytes() {
        try {
            return CompletableFuture.completedFuture(parseTransformSerialize());
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private PacketToTransformingHttpMessageHandler.SizeAndInputStream parseTransformSerialize()
            throws IOException
    {
        var asJsonDoc = toJson(httpParser);
        jsonTransformer.transformJson(asJsonDoc);
        var byteArrayOutputStream = toHttpStream(asJsonDoc);
        var buff = byteArrayOutputStream.toByteArray();
        return new PacketToTransformingHttpMessageHandler.SizeAndInputStream(buff.length, new ByteArrayInputStream(buff));
    }

    private ByteArrayOutputStream toHttpStream(HttpMessageAsJson asJsonDoc) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (var osw = new OutputStreamWriter(baos, StandardCharsets.UTF_8)) {
            osw.append(asJsonDoc.method());
            osw.append(asJsonDoc.uri());
            osw.append(asJsonDoc.protocol());
            osw.append("\n");

            for (var kvpList : asJsonDoc.headers().entrySet()) {
                var key = kvpList.getKey();
                for (var valueEntry : kvpList.getValue()) {
                    osw.append(key);
                    osw.append(":");
                    osw.append(valueEntry);
                    osw.append("\n");
                }
            }
            osw.append("\n\n");
            osw.flush();
        }
        if (asJsonDoc.payload().writePayloadToStream(baos)) {
            //asJsonDoc.payload().
        }
        return baos;
    }

    private HttpMessageAsJson toJson(HttpParser httpParser) {
        return httpParser.asJsonDocument();
    }
}
