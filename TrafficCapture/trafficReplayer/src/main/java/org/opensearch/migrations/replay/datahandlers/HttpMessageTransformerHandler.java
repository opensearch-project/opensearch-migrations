package org.opensearch.migrations.replay.datahandlers;

import org.opensearch.migrations.replay.datahandlers.PacketToTransformingHttpMessageHandler;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class HttpMessageTransformerHandler extends PacketToTransformingHttpMessageHandler {
    public HttpMessageTransformerHandler(IPacketToHttpHandler httpHandler) {
        super(httpHandler, createHttpMessageTransformer());
    }

    private static ByteTransformer createHttpMessageTransformer() {
        return new ByteTransformer() {
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();

            @Override
            public void consumeBytes(byte[] nextRequestPacket) throws IOException {
                byteArrayOutputStream.write(nextRequestPacket);
            }

            @Override
            public SizeAndInputStream getFullyTransformedBytes() throws IOException {
                var buff = byteArrayOutputStream.toByteArray();
                return new SizeAndInputStream(buff.length, new ByteArrayInputStream(buff));
            }
        };
    }
}
