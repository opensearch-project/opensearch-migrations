package org.opensearch.migrations.trafficcapture;

import java.nio.ByteBuffer;

import com.google.protobuf.CodedOutputStream;

import lombok.Getter;
import lombok.NonNull;

@Getter
public class CodedOutputStreamAndByteBufferWrapper implements CodedOutputStreamHolder {
    @NonNull
    private final CodedOutputStream outputStream;
    @NonNull
    private final ByteBuffer byteBuffer;

    public CodedOutputStreamAndByteBufferWrapper(int bufferSize) {
        this.byteBuffer = ByteBuffer.allocate(bufferSize);
        outputStream = CodedOutputStream.newInstance(byteBuffer);
    }

    public int getOutputStreamBytesLimit() {
        return byteBuffer.limit();
    }
}
