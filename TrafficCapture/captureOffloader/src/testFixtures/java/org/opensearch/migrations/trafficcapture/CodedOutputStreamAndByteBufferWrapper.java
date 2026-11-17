package org.opensearch.migrations.trafficcapture;

import java.nio.ByteBuffer;

import com.google.protobuf.CodedOutputStream;

public class CodedOutputStreamAndByteBufferWrapper implements CodedOutputStreamHolder {
    private final CodedOutputStream outputStream;
    private final ByteBuffer byteBuffer;

    public CodedOutputStreamAndByteBufferWrapper(int bufferSize) {
        this.byteBuffer = ByteBuffer.allocate(bufferSize);
        outputStream = CodedOutputStream.newInstance(byteBuffer);
    }

    public CodedOutputStream getOutputStream() {
        return outputStream;
    }

    public ByteBuffer getByteBuffer() {
        return byteBuffer;
    }

    @Override
    public int getOutputStreamBytesLimit() {
        return byteBuffer.limit();
    }
}
