package org.opensearch.migrations.trafficcapture;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Timestamp;
import io.netty.buffer.ByteBuf;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.trafficcapture.protos.EndOfMessageIndicator;
import org.opensearch.migrations.trafficcapture.protos.Exception;
import org.opensearch.migrations.trafficcapture.protos.Read;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.Write;

import java.io.Closeable;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.function.Supplier;

@Slf4j
public class StreamChannelConnectionCaptureSerializer implements
        IChannelConnectionCaptureSerializer, Closeable {

    private final static int MAX_ID_SIZE = 32;

    private final Supplier<CodedOutputStream> codedOutputStreamSupplier;
    private final Function<CodedOutputStream, CompletableFuture> closeHandler;
    private final String idString;
    private CodedOutputStream currentCodedOutputStreamOrNull;
    private int numFlushesSoFar;
    private int firstLineByteLength = -1;
    private int headersByteLength = -1;

    public StreamChannelConnectionCaptureSerializer(String id,
                                                    Supplier<CodedOutputStream> codedOutputStreamSupplier,
                                                    Function<CodedOutputStream, CompletableFuture> closeHandler) throws IOException {
        this.codedOutputStreamSupplier = codedOutputStreamSupplier;
        this.closeHandler = closeHandler;
        assert(id.getBytes(StandardCharsets.UTF_8).length <= MAX_ID_SIZE);
        this.idString = id;
    }

    private static int getWireTypeForFieldIndex(Descriptors.Descriptor d, int fieldNumber) {
        return d.findFieldByNumber(fieldNumber).getLiteType().getWireType();
    }

    private CodedOutputStream currentCodedOutputStream() throws IOException {
        if (currentCodedOutputStreamOrNull != null) {
            return currentCodedOutputStreamOrNull;
        } else {
            currentCodedOutputStreamOrNull = codedOutputStreamSupplier.get();
            currentCodedOutputStreamOrNull.writeString(TrafficStream.ID_FIELD_NUMBER, idString);
            return currentCodedOutputStreamOrNull;
        }
    }

    private void writeTrafficStreamTag(int fieldNumber) throws IOException {
        currentCodedOutputStream().writeTag(fieldNumber,
                getWireTypeForFieldIndex(TrafficStream.getDescriptor(), fieldNumber));
    }

    private void writeObservationTag(int fieldNumber) throws IOException {
        currentCodedOutputStream().writeTag(fieldNumber,
                getWireTypeForFieldIndex(TrafficObservation.getDescriptor(), fieldNumber));
    }

    private void beginWritingObservationToCurrentStream(Instant timestamp, int tag, int bodySize) throws IOException {
        writeTrafficStreamTag(TrafficStream.SUBSTREAM_FIELD_NUMBER);
        final var tsSize = getSizeOfTimestamp(timestamp);
        final var observationTagSize = CodedOutputStream.computeTagSize(tag);
        currentCodedOutputStream().writeUInt32NoTag(tsSize +
                        CodedOutputStream.computeInt32Size(TrafficObservation.TS_FIELD_NUMBER, tsSize) +
                observationTagSize +
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
        currentCodedOutputStream().writeUInt32NoTag(getSizeOfTimestamp(timestamp));

        currentCodedOutputStream().writeInt64(Timestamp.SECONDS_FIELD_NUMBER, timestamp.getEpochSecond());
        if (timestamp.getNano() != 0) {
            currentCodedOutputStream().writeInt32(Timestamp.NANOS_FIELD_NUMBER, timestamp.getNano());
        }
    }

    private void writeByteBufferToCurrentStream(int fieldNum, ByteBuffer byteBuffer) throws IOException {
        if (byteBuffer.remaining() > 0) {
            currentCodedOutputStream().writeByteBuffer(fieldNum, byteBuffer);
        } else {
            currentCodedOutputStream().writeUInt32NoTag(0);
        }
    }


    private void writeByteStringToCurrentStream(int fieldNum, String str) throws IOException {
        if (str.length() > 0) {
            currentCodedOutputStream().writeString(fieldNum, str);
        } else {
            currentCodedOutputStream().writeUInt32NoTag(0);
        }
    }
    
    @Override
    public CompletableFuture<Object> flushCommitAndResetStream(boolean isFinal) throws IOException {
        if (currentCodedOutputStreamOrNull == null && !isFinal) {
            return closeHandler.apply(null);
        }
        var fieldNum = isFinal ? TrafficStream.NUMBEROFTHISLASTCHUNK_FIELD_NUMBER : TrafficStream.NUMBER_FIELD_NUMBER;
        currentCodedOutputStream().writeInt32(fieldNum, ++numFlushesSoFar);
        currentCodedOutputStream().flush();
        var future = closeHandler.apply(currentCodedOutputStream());
        //future.whenComplete((r,t)->{}); // do more cleanup stuff here once the future is complete
        currentCodedOutputStreamOrNull = null;
        return future;
    }

    /**
     * This call is BLOCKING.  Override the Closeable interface - not addCloseEvent.
     *
     * @throws IOException
     */
    @Override
    public void close() throws IOException {
        try {
            flushCommitAndResetStream(true).get();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
    }



    private TrafficObservation.Builder getTrafficObservationBuilder() {
        return TrafficObservation.newBuilder();
    }

    @Override
    public void addBindEvent(Instant timestamp, SocketAddress addr) throws IOException {

    }

    @Override
    public void addConnectEvent(Instant timestamp, SocketAddress remote, SocketAddress local) throws IOException {

    }

    @Override
    public void addDisconnectEvent(Instant timestamp) throws IOException {

    }

    @Override
    public void addCloseEvent(Instant timestamp) throws IOException {

    }

    @Override
    public void addDeregisterEvent(Instant timestamp) throws IOException {

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

    private void addStringMessage(int observationFieldNumber, int dataFieldNumber,
                                Instant timestamp, String str) throws IOException {
        int dataSize = 0;
        int lengthSize = 1;
        if (str.length() > 0) {
            dataSize = CodedOutputStream.computeStringSize(dataFieldNumber, str);
            lengthSize = currentCodedOutputStream().computeInt32SizeNoTag(dataSize);
        }
        beginWritingObservationToCurrentStream(timestamp, observationFieldNumber,
                dataSize+lengthSize);
        if (dataSize > 0) {
            currentCodedOutputStream().writeInt32NoTag(dataSize);
        }
        writeByteStringToCurrentStream(dataFieldNumber, str);
    }

    private void addDataMessage(int observationFieldNumber, int dataFieldNumber,
                                Instant timestamp, ByteBuf buffer) throws IOException {
        var byteBuffer = buffer.nioBuffer();
        int dataSize = 0;
        int lengthSize = 1;
        if (byteBuffer.remaining() > 0) {
            dataSize = CodedOutputStream.computeByteBufferSize(dataFieldNumber, byteBuffer);
            lengthSize = currentCodedOutputStream().computeInt32SizeNoTag(dataSize);
        }
        beginWritingObservationToCurrentStream(timestamp, observationFieldNumber,
                dataSize+lengthSize);
        if (dataSize > 0) {
            currentCodedOutputStream().writeInt32NoTag(dataSize);
        }
        writeByteBufferToCurrentStream(dataFieldNumber, byteBuffer);
    }

    @Override
    public void addReadEvent(Instant timestamp, ByteBuf buffer) throws IOException {
        addDataMessage(TrafficObservation.READ_FIELD_NUMBER, Read.DATA_FIELD_NUMBER, timestamp, buffer);
    }

    @Override
    public void addWriteEvent(Instant timestamp, ByteBuf buffer) throws IOException {
        addDataMessage(TrafficObservation.WRITE_FIELD_NUMBER, Write.DATA_FIELD_NUMBER, timestamp, buffer);
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
        addStringMessage(TrafficObservation.EXCEPTION_FIELD_NUMBER, Exception.MESSAGE_FIELD_NUMBER,
                timestamp, t.getMessage());
    }

    @Override
    public void addEndOfFirstLineIndicator(int numBytes) throws IOException {
        firstLineByteLength = numBytes;
    }

    @Override
    public void addEndOfHeadersIndicator(int numBytes) throws IOException {
        headersByteLength = numBytes;
    }

    @Override
    public void commitEndOfHttpMessageIndicator(Instant timestamp)
            throws IOException
    {
        writeEndOfHttpMessage(timestamp);
        this.firstLineByteLength = -1;
        this.headersByteLength = -1;
    }

    private void writeEndOfHttpMessage(Instant timestamp) throws IOException {
        int eomPairSize = CodedOutputStream.computeInt32Size(EndOfMessageIndicator.FIRSTLINEBYTELENGTH_FIELD_NUMBER, firstLineByteLength) +
                CodedOutputStream.computeInt32Size(EndOfMessageIndicator.HEADERSBYTELENGTH_FIELD_NUMBER, headersByteLength);
        int eomDataSize = eomPairSize + CodedOutputStream.computeInt32SizeNoTag(eomPairSize);
        beginWritingObservationToCurrentStream(timestamp, TrafficObservation.ENDOFMESSAGEINDICATOR_FIELD_NUMBER, eomDataSize);
        currentCodedOutputStream().writeUInt32NoTag(eomPairSize);
        currentCodedOutputStream().writeInt32(EndOfMessageIndicator.FIRSTLINEBYTELENGTH_FIELD_NUMBER, firstLineByteLength);
        currentCodedOutputStream().writeInt32(EndOfMessageIndicator.HEADERSBYTELENGTH_FIELD_NUMBER, headersByteLength);
    }
}
