package org.opensearch.migrations.trafficcapture;

import com.google.protobuf.CodedOutputStream;
import lombok.Getter;
import lombok.NonNull;

import java.nio.ByteBuffer;

public class CodedOutputStreamAndByteBufferWrapper implements CodedOutputStreamHolder {
    @NonNull
    @Getter
    private final CodedOutputStream outputStream;
    @NonNull
    @Getter
    private final ByteBuffer byteBuffer;

    public CodedOutputStreamAndByteBufferWrapper(int bufferSize) {
        this.byteBuffer = ByteBuffer.allocate(bufferSize);
        outputStream = CodedOutputStream.newInstance(byteBuffer);
    }
}
