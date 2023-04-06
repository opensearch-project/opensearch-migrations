package org.opensearch.migrations.trafficcapture;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Timestamp;
import io.netty.buffer.ByteBuf;
import org.opensearch.migrations.trafficcapture.protos.Exception;
import org.opensearch.migrations.trafficcapture.protos.Read;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import java.io.Closeable;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class StreamChannelConnectionCaptureSerializer implements
        IChannelConnectionCaptureOffloader, Closeable {

    private final static int MAX_ID_SIZE = 32;

    private final Supplier<CodedOutputStream> codedOutputStreamSupplier;
    private final Consumer<CodedOutputStream> flushHandler;
    private CodedOutputStream currentCodedOutputStream;
    private int numFlushesSoFar;

    private static int getSpaceRequiredForNextDataObservation(int size) {
        return size + 20;
    }

    private static int getWireTypeForFieldIndex(Descriptors.Descriptor d, int fieldNumber) {
        return d.findFieldByNumber(fieldNumber).getLiteType().getWireType();
    }

    private void writeTrafficStreamTag(int fieldNumber) throws IOException {
        currentCodedOutputStream.writeTag(fieldNumber,
                getWireTypeForFieldIndex(TrafficStream.getDescriptor(), fieldNumber));
    }

    private void writeObservationTag(int fieldNumber) throws IOException {
        currentCodedOutputStream.writeTag(fieldNumber,
                getWireTypeForFieldIndex(TrafficObservation.getDescriptor(), fieldNumber));
    }

    public StreamChannelConnectionCaptureSerializer(String id,
                                                    Supplier<CodedOutputStream> codedOutputStreamSupplier,
                                                    Consumer<CodedOutputStream> flushHandler) throws IOException {
        this.codedOutputStreamSupplier = codedOutputStreamSupplier;
        this.flushHandler = flushHandler;
        currentCodedOutputStream = codedOutputStreamSupplier.get();
        assert(id.getBytes(StandardCharsets.UTF_8).length <= MAX_ID_SIZE);
        currentCodedOutputStream.writeString(TrafficStream.ID_FIELD_NUMBER, id);
    }

    private void beginWritingObservationToCurrentStream(int tag, int bodySize) throws IOException {
        writeTrafficStreamTag(TrafficStream.SUBSTREAM_FIELD_NUMBER);
        long millis = System.currentTimeMillis();
        currentCodedOutputStream.writeUInt32NoTag(getSizeOfTimestamp(millis) +
                CodedOutputStream.computeTagSize(TrafficObservation.TS_FIELD_NUMBER) +
                CodedOutputStream.computeTagSize(tag) +
                bodySize);
        writeTimestampForNowToCurrentStream(millis);
        writeObservationTag(tag);
    }

    private int getSizeOfTimestamp(long millis) throws IOException {
        int seconds = (int) (millis / 1000);
        int nanos = (int) ((millis % 1000) * 1000000);
        var secSize = CodedOutputStream.computeInt64Size(Timestamp.SECONDS_FIELD_NUMBER, seconds);
        var nanoSize = nanos == 0 ? 0 : CodedOutputStream.computeInt32Size(Timestamp.NANOS_FIELD_NUMBER, nanos);
        return secSize + nanoSize;
    }

    private void writeTimestampForNowToCurrentStream(long millis) throws IOException {
        writeObservationTag(TrafficObservation.TS_FIELD_NUMBER);
        currentCodedOutputStream.writeUInt32NoTag(getSizeOfTimestamp(millis));
        int seconds = (int) (millis / 1000);
        int nanos = (int) ((millis % 1000) * 1000000);

        currentCodedOutputStream.writeInt64(Timestamp.SECONDS_FIELD_NUMBER, seconds);
        if (nanos != 0) {
            currentCodedOutputStream.writeInt32(Timestamp.NANOS_FIELD_NUMBER, nanos);
        }
    }

    private void writeByteBufferToCurrentStream(int fieldNum, ByteBuffer byteBuffer) throws IOException {
        if (byteBuffer.remaining() > 0) {
            currentCodedOutputStream.writeByteBuffer(fieldNum, byteBuffer);
        } else {
            currentCodedOutputStream.writeUInt32NoTag(0);
        }
    }


    private void writeByteStringToCurrentStream(int fieldNum, String str) throws IOException {
        if (str.length() > 0) {
            currentCodedOutputStream.writeString(fieldNum, str);
        } else {
            currentCodedOutputStream.writeUInt32NoTag(0);
        }
    }

    private void flush(boolean isFinal) throws IOException {
        var fieldNum = isFinal ? TrafficStream.NUMBEROFTHISLASTCHUNK_FIELD_NUMBER : TrafficStream.NUMBER_FIELD_NUMBER;
        currentCodedOutputStream.writeInt32(fieldNum, ++numFlushesSoFar);
        flushHandler.accept(currentCodedOutputStream);
    }

    @Override
    public void close() throws IOException {
        if (currentCodedOutputStream.getTotalBytesWritten() > 0) {
            flush(true);
            currentCodedOutputStream = null;
        }
    }

    private TrafficObservation.Builder getTrafficObservationBuilder() {
        return TrafficObservation.newBuilder();
    }

    @Override
    public void addBindEvent(SocketAddress addr) throws IOException {
        //beginWritingObservationToCurrentStream(0);
    }

    @Override
    public void addConnectEvent(SocketAddress remote, SocketAddress local) throws IOException {
        //beginWritingObservationToCurrentStream(0);
    }

    @Override
    public void addDisconnectEvent() throws IOException {
        //beginWritingObservationToCurrentStream(0);
    }

    @Override
    public void addCloseEvent() throws IOException {
        //beginWritingObservationToCurrentStream(0);
    }

    @Override
    public void addDeregisterEvent() throws IOException {
        //beginWritingObservationToCurrentStream(0);
    }

    static abstract class BufRangeConsumer {
        abstract void accept(byte[] buff, int offset, int len);
    }
//
//    private void addByteBuf(ByteBuf buffer, BufRangeConsumer bufConsumer) {
//        int buffSize = buffer.readableBytes();
//        buffer.markReaderIndex();
//        if (bufferedCapacityLeft < getSpaceRequiredForNextDataObservation(buffSize)) {
//            byte[] buf = new byte[buffSize];
//            buffer.readBytes(buf);
//            buffer.
//            buffer.readBytes()
//            bufConsumer.accept(buf, 0, buffSize);
//        } else {
//
//        }
//        buffer.resetReaderIndex();
//
//    }

    @Override
    public void addReadEvent(ByteBuf buffer) throws IOException {
        var byteBuffer = buffer.nioBuffer();
        var dataSize = CodedOutputStream.computeByteBufferSize(Read.DATA_FIELD_NUMBER, byteBuffer);
        beginWritingObservationToCurrentStream(TrafficObservation.READ_FIELD_NUMBER, dataSize);
        writeByteBufferToCurrentStream(Read.DATA_FIELD_NUMBER, byteBuffer);
    }

    @Override
    public void addWriteEvent(ByteBuf buffer) {

    }

    @Override
    public void addFlushEvent() {

    }

    @Override
    public void addChannelRegisteredEvent() {

    }

    @Override
    public void addChannelUnregisteredEvent() {

    }

    @Override
    public void addChannelActiveEvent() {

    }

    @Override
    public void addChannelInactiveEvent() {

    }

    @Override
    public void addChannelReadEvent() {

    }

    @Override
    public void addChannelReadCompleteEvent() {

    }

    @Override
    public void addUserEventTriggeredEvent() {

    }

    @Override
    public void addChannelWritabilityChangedEvent() {

    }

    @Override
    public void addExceptionCaughtEvent(Throwable t) throws IOException {
        String exceptionMessage = t.getMessage();
        var exceptionObjectSize = CodedOutputStream.computeStringSize(Exception.MESSAGE_FIELD_NUMBER, exceptionMessage);
        beginWritingObservationToCurrentStream(TrafficObservation.EXCEPTION_FIELD_NUMBER, exceptionObjectSize);
        writeByteStringToCurrentStream(Exception.MESSAGE_FIELD_NUMBER, exceptionMessage);
    }
}
