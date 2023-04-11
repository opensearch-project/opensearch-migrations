package org.opensearch.migrations.trafficcapture;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Timestamp;
import io.netty.buffer.ByteBuf;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.trafficcapture.protos.Exception;
import org.opensearch.migrations.trafficcapture.protos.Read;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import java.io.Closeable;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Slf4j
public class StreamChannelConnectionCaptureSerializer implements
        IChannelConnectionCaptureSerializer, Closeable {

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

    private void beginWritingObservationToCurrentStream(Instant timestamp, int tag, int bodySize) throws IOException {
        writeTrafficStreamTag(TrafficStream.SUBSTREAM_FIELD_NUMBER);
        long millis = System.currentTimeMillis();
        currentCodedOutputStream.writeUInt32NoTag(getSizeOfTimestamp(timestamp) +
                CodedOutputStream.computeTagSize(TrafficObservation.TS_FIELD_NUMBER) +
                CodedOutputStream.computeTagSize(tag) +
                bodySize);
        writeTimestampForNowToCurrentStream(timestamp);
        writeObservationTag(tag);
    }

    private int getSizeOfTimestamp(Instant t) throws IOException {
        long seconds = t.getEpochSecond();
        int nanos = t.getNano();
        var secSize = CodedOutputStream.computeInt64Size(Timestamp.SECONDS_FIELD_NUMBER, seconds);
        var nanoSize = nanos == 0 ? 0 : CodedOutputStream.computeInt32Size(Timestamp.NANOS_FIELD_NUMBER, nanos);
        return secSize + nanoSize;
    }

    private void writeTimestampForNowToCurrentStream(Instant timestamp) throws IOException {
        writeObservationTag(TrafficObservation.TS_FIELD_NUMBER);
        currentCodedOutputStream.writeUInt32NoTag(getSizeOfTimestamp(timestamp));

        currentCodedOutputStream.writeInt64(Timestamp.SECONDS_FIELD_NUMBER, timestamp.getEpochSecond());
        if (timestamp.getNano() != 0) {
            currentCodedOutputStream.writeInt32(Timestamp.NANOS_FIELD_NUMBER, timestamp.getNano());
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

    @Override
    public void flush(boolean isFinal) throws IOException {
        var fieldNum = isFinal ? TrafficStream.NUMBEROFTHISLASTCHUNK_FIELD_NUMBER : TrafficStream.NUMBER_FIELD_NUMBER;
        currentCodedOutputStream.writeInt32(fieldNum, ++numFlushesSoFar);
        currentCodedOutputStream.flush();
        flushHandler.accept(currentCodedOutputStream);
    }

    /**
     * Override the Closeable interface - not addCloseEvent
     * @throws IOException
     */
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
    public void addBindEvent(Instant timestamp, SocketAddress addr) throws IOException {
        //beginWritingObservationToCurrentStream(0);
    }

    @Override
    public void addConnectEvent(Instant timestamp, SocketAddress remote, SocketAddress local) throws IOException {
        //beginWritingObservationToCurrentStream(0);
    }

    @Override
    public void addDisconnectEvent(Instant timestamp) throws IOException {
        //beginWritingObservationToCurrentStream(0);
    }

    @Override
    public void addCloseEvent(Instant timestamp) throws IOException {
        //beginWritingObservationToCurrentStream(0);
    }

    @Override
    public void addDeregisterEvent(Instant timestamp) throws IOException {
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
    public void addReadEvent(Instant timestamp, ByteBuf buffer) throws IOException {
        var byteBuffer = buffer.nioBuffer();
        var dataSize = CodedOutputStream.computeByteBufferSize(Read.DATA_FIELD_NUMBER, byteBuffer);
        beginWritingObservationToCurrentStream(timestamp, TrafficObservation.READ_FIELD_NUMBER, dataSize);
        writeByteBufferToCurrentStream(Read.DATA_FIELD_NUMBER, byteBuffer);
        log.warn("Flushing read because the upstream callers don't know when to flush yet");
        //flush(false);
    }

    @Override
    public void addWriteEvent(Instant timestamp, ByteBuf buffer) throws IOException {

    }

    @Override
    public void addFlushEvent(Instant timestamp) throws IOException {

    }

    @Override
    public void addChannelRegisteredEvent(Instant timestamp) throws IOException {

    }

    @Override
    public void addChannelUnregisteredEvent(Instant timestamp) throws IOException {

    }

    @Override
    public void addChannelActiveEvent(Instant timestamp) throws IOException {

    }

    @Override
    public void addChannelInactiveEvent(Instant timestamp) throws IOException {

    }

    @Override
    public void addChannelReadEvent(Instant timestamp) throws IOException {

    }

    @Override
    public void addChannelReadCompleteEvent(Instant timestamp) throws IOException {

    }

    @Override
    public void addUserEventTriggeredEvent(Instant timestamp) throws IOException {

    }

    @Override
    public void addChannelWritabilityChangedEvent(Instant timestamp) throws IOException {

    }

    @Override
    public void addExceptionCaughtEvent(Instant timestamp, Throwable t) throws IOException {
        String exceptionMessage = t.getMessage();
        var exceptionObjectSize =
                CodedOutputStream.computeStringSize(Exception.MESSAGE_FIELD_NUMBER, exceptionMessage);
        beginWritingObservationToCurrentStream(timestamp, TrafficObservation.EXCEPTION_FIELD_NUMBER, exceptionObjectSize);
        writeByteStringToCurrentStream(Exception.MESSAGE_FIELD_NUMBER, exceptionMessage);
    }

    @Override
    public void addEndOfFirstLineIndicator(Instant timestamp, int characterIndex) throws IOException {
        beginWritingObservationToCurrentStream(timestamp, TrafficObservation.ENDOFLINE_FIELD_NUMBER, 0);
    }

    @Override
    public void addEndOfHeadersIndicator(Instant timestamp, int characterIndex) throws IOException {
        beginWritingObservationToCurrentStream(timestamp, TrafficObservation.ENDOFHEADERS_FIELD_NUMBER, 0);
    }

    @Override
    public void addEndOfHttpMessageIndicator(Instant timestamp) throws IOException {
        beginWritingObservationToCurrentStream(timestamp, TrafficObservation.ENDOFMESSAGEINDICATOR_FIELD_NUMBER, 0);
    }
}
