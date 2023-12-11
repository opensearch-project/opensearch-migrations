package org.opensearch.migrations.trafficcapture;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Timestamp;
import io.netty.buffer.ByteBuf;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import org.opensearch.migrations.trafficcapture.protos.CloseObservation;
import org.opensearch.migrations.trafficcapture.protos.ConnectionExceptionObservation;
import org.opensearch.migrations.trafficcapture.protos.EndOfMessageIndication;
import org.opensearch.migrations.trafficcapture.protos.EndOfSegmentsIndication;
import org.opensearch.migrations.trafficcapture.protos.ReadObservation;
import org.opensearch.migrations.trafficcapture.protos.ReadSegmentObservation;
import org.opensearch.migrations.trafficcapture.protos.RequestIntentionallyDropped;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.WriteObservation;
import org.opensearch.migrations.trafficcapture.protos.WriteSegmentObservation;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * At a basic level, this class aims to be a generic serializer which can receive ByteBuffer data and serialize the data
 * into the defined Protobuf format {@link org.opensearch.migrations.trafficcapture.protos.TrafficStream}, and then write
 * this formatted data to the provided CodedOutputStream.
 *
 * Commented throughout the class are example markers such as (e.g. 1: "1234ABCD") which line up with the textual
 * representation of this Protobuf format to be used as a guide as fields are written. An example TrafficStream can
 * also be visualized below for reference.
 *
 * 1: "91ba4f3a-0b34-11ee-be56-0242ac120002"
 * 5: "5ae27fca-0ac4-11ee-be56-0242ac120002"
 * 2 {
 *   1 {
 *     1: 1683655127
 *     2: 682312000
 *   }
 *   4 {
 *     1: "POST /test-index/_bulk?pretty…”
 *   }
 * }
 * 2 {
 *   1 {
 *     1: 1683655127
 *     2: 683973000
 *   }
 *   15 {
 *     1: 38
 *     2: 105
 *   }
 * }
 * 3: 1
 */
@Slf4j
public class StreamChannelConnectionCaptureSerializer<T> implements IChannelConnectionCaptureSerializer<T> {

    private static final int MAX_ID_SIZE = 96;

    private boolean readObservationsAreWaitingForEom;
    private int eomsSoFar;
    private int numFlushesSoFar;
    private int firstLineByteLength = -1;
    private int headersByteLength = -1;
    private boolean streamHasBeenClosed;

    private final StreamLifecycleManager<T> streamManager;
    private final String nodeIdString;
    private final String connectionIdString;
    private CodedOutputStreamHolder currentCodedOutputStreamHolderOrNull;

    public StreamChannelConnectionCaptureSerializer(String nodeId, String connectionId,
                                                    @NonNull StreamLifecycleManager<T> streamLifecycleManager) {
        this.streamManager = streamLifecycleManager;
        assert (nodeId == null ? 0 : CodedOutputStream.computeStringSize(TrafficStream.NODEID_FIELD_NUMBER, nodeId)) +
                CodedOutputStream.computeStringSize(TrafficStream.CONNECTIONID_FIELD_NUMBER, connectionId)
                <= MAX_ID_SIZE;
        this.connectionIdString = connectionId;
        this.nodeIdString = nodeId;
    }

    private static int getWireTypeForFieldIndex(Descriptors.Descriptor d, int fieldNumber) {
        return d.findFieldByNumber(fieldNumber).getLiteType().getWireType();
    }

    private CodedOutputStream getOrCreateCodedOutputStream() throws IOException {
        if (streamHasBeenClosed) {
            // In an abundance of caution, flip the state back to basically act like a whole new
            // stream is being setup
            log.error("This serializer was already marked as closed previously.  State is being reset to match " +
                    "a new serializer, but this signals a serious issue in the usage of this serializer.");
            readObservationsAreWaitingForEom = false;
            eomsSoFar = 0;
            numFlushesSoFar = 0;
            firstLineByteLength = -1;
            headersByteLength = -1;
            streamHasBeenClosed = false;
        }
        if (currentCodedOutputStreamHolderOrNull != null) {
            return currentCodedOutputStreamHolderOrNull.getOutputStream();
        } else {
            currentCodedOutputStreamHolderOrNull = streamManager.createStream();
            var currentCodedOutputStream = currentCodedOutputStreamHolderOrNull.getOutputStream();
            // e.g. 1: "1234ABCD"
            currentCodedOutputStream.writeString(TrafficStream.CONNECTIONID_FIELD_NUMBER, connectionIdString);
            if (nodeIdString != null) {
                // e.g. 5: "5ae27fca-0ac4-11ee-be56-0242ac120002"
                currentCodedOutputStream.writeString(TrafficStream.NODEID_FIELD_NUMBER, nodeIdString);
            }
            if (eomsSoFar > 0) {
                currentCodedOutputStream.writeInt32(TrafficStream.PRIORREQUESTSRECEIVED_FIELD_NUMBER, eomsSoFar);
            }
            if (readObservationsAreWaitingForEom) {
                currentCodedOutputStream.writeBool(TrafficStream.LASTOBSERVATIONWASUNTERMINATEDREAD_FIELD_NUMBER,
                        readObservationsAreWaitingForEom);
            }
            return currentCodedOutputStream;
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

    /**
     * Will write the beginning fields for a TrafficObservation after first checking if sufficient space exists in the
     * CodedOutputStream and flushing if space does not exist. This should be called before writing any observation to
     * the TrafficStream.
     */
    private void beginSubstreamObservation(Instant timestamp, int captureTagFieldNumber, int captureTagLengthAndContentSize) throws IOException {
        final var tsContentSize = CodedOutputStreamSizeUtil.getSizeOfTimestamp(timestamp);
        final var tsTagSize = CodedOutputStream.computeInt32Size(TrafficObservation.TS_FIELD_NUMBER, tsContentSize);
        final var captureTagNoLengthSize = CodedOutputStream.computeTagSize(captureTagFieldNumber);
        final var observationContentSize = tsTagSize + tsContentSize + captureTagNoLengthSize + captureTagLengthAndContentSize;
        // Ensure space is available before starting an observation
        if (getOrCreateCodedOutputStream().spaceLeft() <
            CodedOutputStreamSizeUtil.bytesNeededForObservationAndClosingIndex(observationContentSize, numFlushesSoFar + 1))
        {
            flushCommitAndResetStream(false);
        }
        // e.g. 2 {
        writeTrafficStreamTag(TrafficStream.SUBSTREAM_FIELD_NUMBER);
        // Write observation content length
        getOrCreateCodedOutputStream().writeUInt32NoTag(observationContentSize);
        // e.g. 1 { 1: 1234 2: 1234 }
        writeTimestampForNowToCurrentStream(timestamp);
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
    public CompletableFuture<T> flushCommitAndResetStream(boolean isFinal) throws IOException {
        if (streamHasBeenClosed || (currentCodedOutputStreamHolderOrNull == null && !isFinal)) {
            return CompletableFuture.completedFuture(null);
        }
        CodedOutputStream currentStream = getOrCreateCodedOutputStream();
        var fieldNum = isFinal ? TrafficStream.NUMBEROFTHISLASTCHUNK_FIELD_NUMBER : TrafficStream.NUMBER_FIELD_NUMBER;
        // e.g. 3: 1
        currentStream.writeInt32(fieldNum, ++numFlushesSoFar);
        log.trace("Flushing the current CodedOutputStream for {}.{}", connectionIdString, numFlushesSoFar);
        currentStream.flush();
        assert currentStream == currentCodedOutputStreamHolderOrNull.getOutputStream() : "Expected the stream that " +
                "is being finalized to be the same stream contained by currentCodedOutputStreamHolderOrNull";
        var future = streamManager.closeStream(currentCodedOutputStreamHolderOrNull, numFlushesSoFar);
        currentCodedOutputStreamHolderOrNull = null;
        if (isFinal) {
            streamHasBeenClosed = true;
        }
        return future;
    }

    @Override
    public void cancelCaptureForCurrentRequest(Instant timestamp) throws IOException {
        beginSubstreamObservation(timestamp, TrafficObservation.REQUESTDROPPED_FIELD_NUMBER, 1);
        getOrCreateCodedOutputStream().writeMessage(TrafficObservation.REQUESTDROPPED_FIELD_NUMBER,
                RequestIntentionallyDropped.getDefaultInstance());
        this.readObservationsAreWaitingForEom = false;
        this.firstLineByteLength = -1;
        this.headersByteLength = -1;
    }

    @Override
    public void addBindEvent(Instant timestamp, SocketAddress addr) throws IOException {
        // not implemented for this serializer.  The v1.0 version of the replayer will ignore this type of observation
    }

    @Override
    public void addConnectEvent(Instant timestamp, SocketAddress remote, SocketAddress local) throws IOException {
        // not implemented for this serializer.  The v1.0 version of the replayer will ignore this type of observation
    }

    @Override
    public void addDisconnectEvent(Instant timestamp) throws IOException {
        // not implemented for this serializer.  The v1.0 version of the replayer will ignore this type of observation
    }

    @Override
    public void addCloseEvent(Instant timestamp) throws IOException {
        beginSubstreamObservation(timestamp, TrafficObservation.CLOSE_FIELD_NUMBER, 1);
        getOrCreateCodedOutputStream().writeMessage(TrafficObservation.CLOSE_FIELD_NUMBER, CloseObservation.getDefaultInstance());
    }

    @Override
    public void addDeregisterEvent(Instant timestamp) throws IOException {
        // not implemented for this serializer.  The v1.0 version of the replayer will ignore this type of observation
    }

    private void addStringMessage(int captureFieldNumber, int dataFieldNumber,
                                  Instant timestamp, String str) throws IOException {
        int dataSize = 0;
        int lengthSize = 1;
        if (str.length() > 0) {
            dataSize = CodedOutputStream.computeStringSize(dataFieldNumber, str);
            lengthSize = CodedOutputStream.computeInt32SizeNoTag(dataSize);
        }
        beginSubstreamObservation(timestamp, captureFieldNumber, dataSize + lengthSize);
        // e.g. 4 {
        writeObservationTag(captureFieldNumber);
        if (dataSize > 0) {
            getOrCreateCodedOutputStream().writeInt32NoTag(dataSize);
        }
        writeByteStringToCurrentStream(dataFieldNumber, str);
    }

    private void addDataMessage(int captureFieldNumber, int dataFieldNumber, Instant timestamp, ByteBuf buffer) throws IOException {
        var byteBuffer = buffer.nioBuffer();
        int segmentFieldNumber;
        int segmentDataFieldNumber;
        if (captureFieldNumber == TrafficObservation.READ_FIELD_NUMBER) {
            segmentFieldNumber = TrafficObservation.READSEGMENT_FIELD_NUMBER;
            segmentDataFieldNumber = ReadSegmentObservation.DATA_FIELD_NUMBER;
        }
        else {
            segmentFieldNumber = TrafficObservation.WRITESEGMENT_FIELD_NUMBER;
            segmentDataFieldNumber = WriteSegmentObservation.DATA_FIELD_NUMBER;
        }

        // The message bytes here are not optimizing for space and instead are calculated on the worst case estimate of
        // the potentially required bytes for simplicity. This could leave ~5 bytes of unused space in the CodedOutputStream
        // when considering the case of a message that does not need segments or for the case of a smaller segment created
        // from a much larger message
        int messageAndOverheadBytesLeft = CodedOutputStreamSizeUtil.maxBytesNeededForASegmentedObservation(timestamp,
            segmentFieldNumber, segmentDataFieldNumber, byteBuffer);
        int trafficStreamOverhead = messageAndOverheadBytesLeft - byteBuffer.capacity();

        // Ensure that space for at least one data byte and overhead exists, otherwise a flush is necessary.
        if (trafficStreamOverhead + 1 >= getOrCreateCodedOutputStream().spaceLeft()) {
            flushCommitAndResetStream(false);
        }

        // If our message is empty or can fit in the current CodedOutputStream no chunking is needed, and we can continue
        if (byteBuffer.limit() == 0 || messageAndOverheadBytesLeft <= getOrCreateCodedOutputStream().spaceLeft()) {
            int minExpectedSpaceAfterObservation = getOrCreateCodedOutputStream().spaceLeft() - messageAndOverheadBytesLeft;
            addSubstreamMessage(captureFieldNumber, dataFieldNumber, timestamp, byteBuffer);
            observationSizeSanityCheck(minExpectedSpaceAfterObservation, captureFieldNumber);
            return;
        }

        while(byteBuffer.position() < byteBuffer.limit()) {
            int availableCOSSpace = getOrCreateCodedOutputStream().spaceLeft();
            int chunkBytes = messageAndOverheadBytesLeft > availableCOSSpace ? availableCOSSpace - trafficStreamOverhead : byteBuffer.limit() - byteBuffer.position();
            ByteBuffer bb = byteBuffer.slice();
            bb.limit(chunkBytes);
            bb = bb.slice();
            byteBuffer.position(byteBuffer.position() + chunkBytes);
            addSubstreamMessage(segmentFieldNumber, segmentDataFieldNumber, timestamp, bb);
            int minExpectedSpaceAfterObservation = availableCOSSpace - chunkBytes - trafficStreamOverhead;
            observationSizeSanityCheck(minExpectedSpaceAfterObservation, segmentFieldNumber);
            // 1 to N-1 chunked messages
            if (byteBuffer.position() < byteBuffer.limit()) {
                flushCommitAndResetStream(false);
                messageAndOverheadBytesLeft = messageAndOverheadBytesLeft - chunkBytes;
            }
        }
        writeEndOfSegmentMessage(timestamp);

    }

    private void addSubstreamMessage(int captureFieldNumber, int dataFieldNumber, int dataCountFieldNumber, int dataCount,
        Instant timestamp, java.nio.ByteBuffer byteBuffer) throws IOException {
        int dataSize = 0;
        int segmentCountSize = 0;
        int captureClosureLength = 1;
        CodedOutputStream codedOutputStream = getOrCreateCodedOutputStream();
        if (dataCountFieldNumber > 0) {
            segmentCountSize = CodedOutputStream.computeInt32Size(dataCountFieldNumber, dataCount);
        }
        if (byteBuffer.remaining() > 0) {
            dataSize = CodedOutputStream.computeByteBufferSize(dataFieldNumber, byteBuffer);
            captureClosureLength = CodedOutputStream.computeInt32SizeNoTag(dataSize + segmentCountSize);
        }
        beginSubstreamObservation(timestamp, captureFieldNumber, captureClosureLength + dataSize + segmentCountSize);
        // e.g. 4 {
        writeObservationTag(captureFieldNumber);
        if (dataSize > 0) {
            // Write size of data after capture tag
            codedOutputStream.writeInt32NoTag(dataSize + segmentCountSize);
        }
        // Write segment count field for segment captures
        if (dataCountFieldNumber > 0) {
            codedOutputStream.writeInt32(dataCountFieldNumber, dataCount);
        }
        // Write data field
        writeByteBufferToCurrentStream(dataFieldNumber, byteBuffer);
        if (captureFieldNumber == TrafficObservation.READ_FIELD_NUMBER ||
                captureFieldNumber == TrafficObservation.READSEGMENT_FIELD_NUMBER) {
            this.readObservationsAreWaitingForEom = true;
        }
    }

    private void addSubstreamMessage(int captureFieldNumber, int dataFieldNumber, Instant timestamp,
        java.nio.ByteBuffer byteBuffer) throws IOException {
        addSubstreamMessage(captureFieldNumber, dataFieldNumber, 0, 0, timestamp, byteBuffer);
    }

    @Override
    public void addReadEvent(Instant timestamp, ByteBuf buffer) throws IOException {
        addDataMessage(TrafficObservation.READ_FIELD_NUMBER, ReadObservation.DATA_FIELD_NUMBER, timestamp, buffer);
    }

    @Override
    public void addWriteEvent(Instant timestamp, ByteBuf buffer) throws IOException {
        addDataMessage(TrafficObservation.WRITE_FIELD_NUMBER, WriteObservation.DATA_FIELD_NUMBER, timestamp, buffer);
    }

    @Override
    public void addFlushEvent(Instant timestamp) throws IOException {
        // not implemented for this serializer.  The v1.0 version of the replayer will ignore this type of observation
    }

    @Override
    public void addChannelRegisteredEvent(Instant timestamp) throws IOException {
        // not implemented for this serializer.  The v1.0 version of the replayer will ignore this type of observation
    }

    @Override
    public void addChannelUnregisteredEvent(Instant timestamp) throws IOException {
        // not implemented for this serializer.  The v1.0 version of the replayer will ignore this type of observation
    }

    @Override
    public void addChannelActiveEvent(Instant timestamp) throws IOException {
        // not implemented for this serializer.  The v1.0 version of the replayer will ignore this type of observation
    }

    @Override
    public void addChannelInactiveEvent(Instant timestamp) throws IOException {
        // not implemented for this serializer.  The v1.0 version of the replayer will ignore this type of observation
    }

    @Override
    public void addChannelReadEvent(Instant timestamp) throws IOException {
        // not implemented for this serializer.  The v1.0 version of the replayer will ignore this type of observation
    }

    @Override
    public void addChannelReadCompleteEvent(Instant timestamp) throws IOException {
        // not implemented for this serializer.  The v1.0 version of the replayer will ignore this type of observation
    }

    @Override
    public void addUserEventTriggeredEvent(Instant timestamp) throws IOException {
        // not implemented for this serializer.  The v1.0 version of the replayer will ignore this type of observation
    }

    @Override
    public void addChannelWritabilityChangedEvent(Instant timestamp) throws IOException {
        // not implemented for this serializer.  The v1.0 version of the replayer will ignore this type of observation
    }

    @Override
    public void addExceptionCaughtEvent(Instant timestamp, Throwable t) throws IOException {
        addStringMessage(TrafficObservation.CONNECTIONEXCEPTION_FIELD_NUMBER,
                ConnectionExceptionObservation.MESSAGE_FIELD_NUMBER,
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
        this.readObservationsAreWaitingForEom = false;
        ++this.eomsSoFar;
        this.firstLineByteLength = -1;
        this.headersByteLength = -1;
    }

    private void writeEndOfHttpMessage(Instant timestamp) throws IOException {
        int eomPairSize = CodedOutputStream.computeInt32Size(EndOfMessageIndication.FIRSTLINEBYTELENGTH_FIELD_NUMBER, firstLineByteLength) +
                CodedOutputStream.computeInt32Size(EndOfMessageIndication.HEADERSBYTELENGTH_FIELD_NUMBER, headersByteLength);
        int eomDataSize = eomPairSize + CodedOutputStream.computeInt32SizeNoTag(eomPairSize);
        beginSubstreamObservation(timestamp, TrafficObservation.ENDOFMESSAGEINDICATOR_FIELD_NUMBER, eomDataSize);
        // e.g. 15 {
        writeObservationTag(TrafficObservation.ENDOFMESSAGEINDICATOR_FIELD_NUMBER);
        getOrCreateCodedOutputStream().writeUInt32NoTag(eomPairSize);
        getOrCreateCodedOutputStream().writeInt32(EndOfMessageIndication.FIRSTLINEBYTELENGTH_FIELD_NUMBER, firstLineByteLength);
        getOrCreateCodedOutputStream().writeInt32(EndOfMessageIndication.HEADERSBYTELENGTH_FIELD_NUMBER, headersByteLength);
    }

    private void writeEndOfSegmentMessage(Instant timestamp) throws IOException {
        beginSubstreamObservation(timestamp, TrafficObservation.SEGMENTEND_FIELD_NUMBER, 1);
        getOrCreateCodedOutputStream().writeMessage(TrafficObservation.SEGMENTEND_FIELD_NUMBER, EndOfSegmentsIndication.getDefaultInstance());
    }

    private void observationSizeSanityCheck(int minExpectedSpaceAfterObservation, int fieldNumber) throws IOException {
        int actualRemainingSpace = getOrCreateCodedOutputStream().spaceLeft();
        if (actualRemainingSpace < minExpectedSpaceAfterObservation || minExpectedSpaceAfterObservation < 0) {
            log.warn("Writing a substream (capture type: {}) for Traffic Stream: {} left {} bytes in the CodedOutputStream but we calculated " +
                    "at least {} bytes remaining, this should be investigated", fieldNumber, connectionIdString + "." + (numFlushesSoFar + 1),
                actualRemainingSpace, minExpectedSpaceAfterObservation);
        }
    }
}
