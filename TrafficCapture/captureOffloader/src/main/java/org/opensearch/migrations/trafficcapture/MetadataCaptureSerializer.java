package org.opensearch.migrations.trafficcapture;

import com.google.protobuf.CodedOutputStream;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Supplier;

public class MetadataCaptureSerializer extends StreamChannelConnectionCaptureSerializer {

    private String requestMethod;

    public MetadataCaptureSerializer(String id, Supplier<CodedOutputStream> codedOutputStreamSupplier,
        BiFunction<IChannelConnectionCaptureOffloader, CodedOutputStream, CompletableFuture> closeHandler) throws IOException {

        super(id, codedOutputStreamSupplier, closeHandler);
    }

    @Override
    public String getRequestMethod() {
        return requestMethod;
    }

    @Override
    public void setRequestMethod(String requestMethod) {
        this.requestMethod = requestMethod;
    }
}
