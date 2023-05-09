package org.opensearch.migrations.trafficcapture;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Timestamp;
import io.netty.buffer.ByteBuf;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.trafficcapture.protos.EndOfMessageIndicator;
import org.opensearch.migrations.trafficcapture.protos.Exception;
import org.opensearch.migrations.trafficcapture.protos.Read;
import org.opensearch.migrations.trafficcapture.protos.ReadSegment;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.Write;
import org.opensearch.migrations.trafficcapture.protos.WriteSegment;

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
public class StreamChannelConnectionCaptureSerializer implements IChannelConnectionCaptureSerializer, Closeable {

    private final static int MAX_ID_SIZE = 32;

    private final Supplier<CodedOutputStream> codedOutputStreamSupplier;
    private final Function<CodedOutputStream, CompletableFuture> closeHandler;
    private final String idString;
    private final int minDataChunkBytes;
    private CodedOutputStream currentCodedOutputStreamOrNull;
    private int numFlushesSoFar;
    private int firstLineByteLength = -1;
    private int headersByteLength = -1;

    public StreamChannelConnectionCaptureSerializer(String id,
                                                    int minDataChunkBytes,
                                                    Supplier<CodedOutputStream> codedOutputStreamSupplier,
                                                    Function<CodedOutputStream, CompletableFuture> closeHandler) throws IOException {
        this.codedOutputStreamSupplier = codedOutputStreamSupplier;
        this.closeHandler = closeHandler;
        assert (id.getBytes(StandardCharsets.UTF_8).length <= MAX_ID_SIZE);
        this.idString = id;
        this.minDataChunkBytes = minDataChunkBytes;
    }

    private static int getWireTypeForFieldIndex(Descriptors.Descriptor d, int fieldNumber) {
        return d.findFieldByNumber(fieldNumber).getLiteType().getWireType();
    }

    private CodedOutputStream getOrCreateCodedOutputStream() throws IOException {
        if (currentCodedOutputStreamOrNull != null) {
            return currentCodedOutputStreamOrNull;
        } else {
            currentCodedOutputStreamOrNull = codedOutputStreamSupplier.get();
            // 1: "1234ABCD"
            currentCodedOutputStreamOrNull.writeString(TrafficStream.ID_FIELD_NUMBER, idString);
            return currentCodedOutputStreamOrNull;
        }
    }

    private void writeTrafficStreamTag(int fieldNumber) throws IOException {
        getOrCreateCodedOutputStream().writeTag(fieldNumber,
                getWireTypeForFieldIndex(TrafficStream.getDescriptor(), fieldNumber));
    }

    private void writeObservationTag(int fieldNumber) throws IOException {
        getOrCreateCodedOutputStream().writeTag(fieldNumber,
                getWireTypeForFieldIndex(TrafficObservation.getDescriptor(), fieldNumber));
    }

    private void beginWritingObservationToCurrentStream(Instant timestamp, int tag, int bodySize) throws IOException {
        // 2 {
        writeTrafficStreamTag(TrafficStream.SUBSTREAM_FIELD_NUMBER);
        final var tsSize = CodedOutputStreamSizeUtil.getSizeOfTimestamp(timestamp);
        final var observationTagSize = CodedOutputStream.computeTagSize(tag);
        // Writing size of [ts size + ts tag + observation/body tag + observation/body size]
        getOrCreateCodedOutputStream().writeUInt32NoTag(tsSize +
            CodedOutputStream.computeInt32Size(TrafficObservation.TS_FIELD_NUMBER, tsSize) +
            observationTagSize +
            bodySize);
        // 1 { 1: .. 2: .. }
        writeTimestampForNowToCurrentStream(timestamp);
        // 4 {
        writeObservationTag(tag);
    }

    private void writeTimestampForNowToCurrentStream(Instant timestamp) throws IOException {
        writeObservationTag(TrafficObservation.TS_FIELD_NUMBER);
        getOrCreateCodedOutputStream().writeUInt32NoTag(CodedOutputStreamSizeUtil.getSizeOfTimestamp(timestamp));

        getOrCreateCodedOutputStream().writeInt64(Timestamp.SECONDS_FIELD_NUMBER, timestamp.getEpochSecond());
        if (timestamp.getNano() != 0) {
            getOrCreateCodedOutputStream().writeInt32(Timestamp.NANOS_FIELD_NUMBER, timestamp.getNano());
        }
    }

    private void writeByteBufferToCurrentStream(int fieldNum, ByteBuffer byteBuffer) throws IOException {
        if (byteBuffer.remaining() > 0) {
            getOrCreateCodedOutputStream().writeByteBuffer(fieldNum, byteBuffer);
        } else {
            getOrCreateCodedOutputStream().writeUInt32NoTag(0);
        }
    }


    private void writeByteStringToCurrentStream(int fieldNum, String str) throws IOException {
        if (str.length() > 0) {
            getOrCreateCodedOutputStream().writeString(fieldNum, str);
        } else {
            getOrCreateCodedOutputStream().writeUInt32NoTag(0);
        }
    }

    @Override
    public CompletableFuture<Object> flushCommitAndResetStream(boolean isFinal) throws IOException {
        if (currentCodedOutputStreamOrNull == null && !isFinal) {
            return closeHandler.apply(null);
        }
        CodedOutputStream currentStream = getOrCreateCodedOutputStream();
        var fieldNum = isFinal ? TrafficStream.NUMBEROFTHISLASTCHUNK_FIELD_NUMBER : TrafficStream.NUMBER_FIELD_NUMBER;
        // 3: 1
        currentStream.writeInt32(fieldNum, ++numFlushesSoFar);
        currentStream.flush();
        var future = closeHandler.apply(currentStream);
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

    private void addStringMessage(int observationFieldNumber, int dataFieldNumber,
                                  Instant timestamp, String str) throws IOException {
        int dataSize = 0;
        int lengthSize = 1;
        if (str.length() > 0) {
            dataSize = CodedOutputStream.computeStringSize(dataFieldNumber, str);
            lengthSize = getOrCreateCodedOutputStream().computeInt32SizeNoTag(dataSize);
        }
        beginWritingObservationToCurrentStream(timestamp, observationFieldNumber,
                dataSize + lengthSize);
        if (dataSize > 0) {
            getOrCreateCodedOutputStream().writeInt32NoTag(dataSize);
        }
        writeByteStringToCurrentStream(dataFieldNumber, str);
    }

    private void addDataMessage(int observationFieldNumber, int dataFieldNumber, Instant timestamp, ByteBuf buffer) throws IOException {
        var byteBuffer = buffer.nioBuffer();
        int segmentFieldNumber,segmentCountFieldNumber,segmentDataFieldNumber;
        if (observationFieldNumber == TrafficObservation.READ_FIELD_NUMBER) {
            segmentFieldNumber = TrafficObservation.READSEGMENT_FIELD_NUMBER;
            segmentCountFieldNumber = ReadSegment.COUNT_FIELD_NUMBER;
            segmentDataFieldNumber = ReadSegment.DATA_FIELD_NUMBER;
        }
        else {
            segmentFieldNumber = TrafficObservation.WRITESEGMENT_FIELD_NUMBER;
            segmentCountFieldNumber = WriteSegment.COUNT_FIELD_NUMBER;
            segmentDataFieldNumber = WriteSegment.DATA_FIELD_NUMBER;
        }

        // The required bytes here are not optimizing for space and instead are calculated on the worst case estimate of
        // the potentially required bytes for simplicity. This could leave ~5 bytes of unused space in the CodedOutputStream
        // when considering the case of a message that does not need segments or the case of a smaller segment created
        // from a much larger message
        int totalMessageBytesRequired = CodedOutputStreamSizeUtil.totalBytesNeededForMessage(timestamp,
            segmentFieldNumber, segmentDataFieldNumber, segmentCountFieldNumber, 2, byteBuffer, numFlushesSoFar + 1);
        int totalDataSurroundingBytesRequired = totalMessageBytesRequired - byteBuffer.capacity();

        if (totalDataSurroundingBytesRequired + minDataChunkBytes >= getOrCreateCodedOutputStream().spaceLeft()) {
            flushCommitAndResetStream(false);
        }

        if (byteBuffer.limit() == 0 || totalMessageBytesRequired <= getOrCreateCodedOutputStream().spaceLeft()) {
            addSubstreamMessage(observationFieldNumber, dataFieldNumber, timestamp, byteBuffer);
            byteBuffer.position(byteBuffer.limit());
        }

        int dataCount = 0;
        while(byteBuffer.position() < byteBuffer.limit()) {
            int availableCOSSpace = getOrCreateCodedOutputStream().spaceLeft();
            int chunkBytes = totalMessageBytesRequired > availableCOSSpace ? availableCOSSpace - totalDataSurroundingBytesRequired : byteBuffer.limit() - byteBuffer.position();
            ByteBuffer bb = byteBuffer.slice(byteBuffer.position(), chunkBytes);
            byteBuffer.position(byteBuffer.position() + chunkBytes);
            addSubstreamMessage(segmentFieldNumber, segmentDataFieldNumber, segmentCountFieldNumber, ++dataCount, timestamp, bb);
            // 1 to N-1 chunked messages
            if (byteBuffer.position() < byteBuffer.limit()) {
                flushCommitAndResetStream(false);
                totalMessageBytesRequired = totalMessageBytesRequired - chunkBytes;
            }
        }

    }

    private void addSubstreamMessage(int observationFieldNumber, int dataFieldNumber, int dataCountFieldNumber, int dataCount,
        Instant timestamp, java.nio.ByteBuffer byteBuffer) throws IOException {
        int dataSize = 0;
        int segmentCountSize = 0;
        int lengthSize = 1;
        CodedOutputStream codedOutputStream = getOrCreateCodedOutputStream();
        // Calculate segment count size
        if (dataCountFieldNumber > 0) {
            segmentCountSize = CodedOutputStream.computeInt32Size(dataCountFieldNumber, dataCount);
        }
        if (byteBuffer.remaining() > 0) {
            // These combined are everything but the capture tag for the capture closure
            dataSize = CodedOutputStream.computeByteBufferSize(dataFieldNumber, byteBuffer);
            lengthSize = CodedOutputStream.computeInt32SizeNoTag(dataSize + segmentCountSize);
        }
        beginWritingObservationToCurrentStream(timestamp, observationFieldNumber, dataSize + segmentCountSize + lengthSize);
        if (dataSize > 0) {
            // Write size of data after observation tag
            codedOutputStream.writeInt32NoTag(dataSize + segmentCountSize);
        }
        // Write segment count field
        if (dataCountFieldNumber > 0) {
            codedOutputStream.writeInt32(dataCountFieldNumber, dataCount);
        }
        // Write data field
        writeByteBufferToCurrentStream(dataFieldNumber, byteBuffer);
    }

    private void addSubstreamMessage(int observationFieldNumber, int dataFieldNumber, Instant timestamp,
        java.nio.ByteBuffer byteBuffer) throws IOException {
        addSubstreamMessage(observationFieldNumber, dataFieldNumber, 0, 0, timestamp, byteBuffer);
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
            throws IOException {
        writeEndOfHttpMessage(timestamp);
        this.firstLineByteLength = -1;
        this.headersByteLength = -1;
    }

    private void writeEndOfHttpMessage(Instant timestamp) throws IOException {
        int eomPairSize = CodedOutputStream.computeInt32Size(EndOfMessageIndicator.FIRSTLINEBYTELENGTH_FIELD_NUMBER, firstLineByteLength) +
                CodedOutputStream.computeInt32Size(EndOfMessageIndicator.HEADERSBYTELENGTH_FIELD_NUMBER, headersByteLength);
        int eomDataSize = eomPairSize + CodedOutputStream.computeInt32SizeNoTag(eomPairSize);
        beginWritingObservationToCurrentStream(timestamp, TrafficObservation.ENDOFMESSAGEINDICATOR_FIELD_NUMBER, eomDataSize);
        getOrCreateCodedOutputStream().writeUInt32NoTag(eomPairSize);
        getOrCreateCodedOutputStream().writeInt32(EndOfMessageIndicator.FIRSTLINEBYTELENGTH_FIELD_NUMBER, firstLineByteLength);
        getOrCreateCodedOutputStream().writeInt32(EndOfMessageIndicator.HEADERSBYTELENGTH_FIELD_NUMBER, headersByteLength);
    }
}
