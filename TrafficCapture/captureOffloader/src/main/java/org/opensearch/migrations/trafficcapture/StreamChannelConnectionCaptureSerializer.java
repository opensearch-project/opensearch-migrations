package org.opensearch.migrations.trafficcapture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.GatheringByteChannel;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

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

import com.google.protobuf.ByteOutput;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Timestamp;
import com.google.protobuf.WireFormat;
import io.netty.buffer.ByteBuf;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

/**
 * This class serves as a generic serializer. Its primary function is to take ByteBuffer data,
 * serialize it into the Protobuf format as defined by
 * {@link org.opensearch.migrations.trafficcapture.protos.TrafficStream}, and then output
 * the formatted data to a given CodedOutputStream.
 * <p>
 * Within the class, example markers are commented (e.g., 1: "9a25a4fffe620014-00034cfa-00000001-d208faac76346d02-864e38e2").
 * These markers correspond to the textual representation of the Protobuf format and serve as a guide
 * for field serialization. Below is a visual representation of an example `TrafficStream` for further reference:
 * <pre>{@code
 * 1: "9a25a4fffe620014-00034cfa-00000001-d208faac76346d02-864e38e2"
 * 5: "5ae27fca-0ac4-11ee-be56-0242ac120002"
 * 2 {
 *   1 {
 *     1: 1683655127
 *     2: 682312000
 *   }
 *   4 {
 *     1: "POST /test-index/_bulk?pretty…"
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
 * }
 * </pre>
 */
@Slf4j
public class StreamChannelConnectionCaptureSerializer<T> implements IChannelConnectionCaptureSerializer<T> {

    // 100 is the default size of netty connectionId and kafka nodeId along with serializationTags
    private static final int MAX_ID_SIZE = 100;

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

    public StreamChannelConnectionCaptureSerializer(
        String nodeId,
        String connectionId,
        @NonNull StreamLifecycleManager<T> streamLifecycleManager
    ) {
        this.streamManager = streamLifecycleManager;
        assert (nodeId == null ? 0 : CodedOutputStream.computeStringSize(TrafficStream.NODEID_FIELD_NUMBER, nodeId))
            + CodedOutputStream.computeStringSize(TrafficStream.CONNECTIONID_FIELD_NUMBER, connectionId) <= MAX_ID_SIZE;
        this.connectionIdString = connectionId;
        this.nodeIdString = nodeId;
    }

    private static int getWireTypeForFieldIndex(Descriptors.Descriptor d, int fieldNumber) {
        return d.findFieldByNumber(fieldNumber).getLiteType().getWireType();
    }

    private CodedOutputStream getOrCreateCodedOutputStream() throws IOException {
        return getOrCreateCodedOutputStreamHolder().getOutputStream();
    }

    private CodedOutputStreamHolder getOrCreateCodedOutputStreamHolder() throws IOException {
        if (streamHasBeenClosed) {
            // In an abundance of caution, flip the state back to basically act like a whole new
            // stream is being setup
            log.error(
                "This serializer was already marked as closed previously.  State is being reset to match "
                    + "a new serializer, but this signals a serious issue in the usage of this serializer."
            );
            readObservationsAreWaitingForEom = false;
            eomsSoFar = 0;
            numFlushesSoFar = 0;
            firstLineByteLength = -1;
            headersByteLength = -1;
            streamHasBeenClosed = false;
        }
        if (currentCodedOutputStreamHolderOrNull != null) {
            return currentCodedOutputStreamHolderOrNull;
        } else {
            currentCodedOutputStreamHolderOrNull = streamManager.createStream();
            var currentCodedOutputStream = currentCodedOutputStreamHolderOrNull.getOutputStream();
            // e.g. <pre> 1: "9a25a4fffe620014-00034cfa-00000001-d208faac76346d02-864e38e2" </pre>
            currentCodedOutputStream.writeString(TrafficStream.CONNECTIONID_FIELD_NUMBER, connectionIdString);
            if (nodeIdString != null) {
                // e.g. <pre> 5: "5ae27fca-0ac4-11ee-be56-0242ac120002" </pre>
                currentCodedOutputStream.writeString(TrafficStream.NODEID_FIELD_NUMBER, nodeIdString);
            }
            if (eomsSoFar > 0) {
                currentCodedOutputStream.writeInt32(TrafficStream.PRIORREQUESTSRECEIVED_FIELD_NUMBER, eomsSoFar);
            }
            if (readObservationsAreWaitingForEom) {
                currentCodedOutputStream.writeBool(
                    TrafficStream.LASTOBSERVATIONWASUNTERMINATEDREAD_FIELD_NUMBER,
                    readObservationsAreWaitingForEom
                );
            }
            return currentCodedOutputStreamHolderOrNull;
        }
    }

    public int currentOutputStreamWriteableSpaceLeft() throws IOException {
        // Writeable bytes is the space left minus the space needed to complete the next flush
        var maxFieldTagNumberToBeWrittenUponStreamFlush = Math.max(
            TrafficStream.NUMBEROFTHISLASTCHUNK_FIELD_NUMBER,
            TrafficStream.NUMBER_FIELD_NUMBER
        );
        var spaceNeededForRecordCreationDuringNextFlush = CodedOutputStream.computeInt32Size(
            maxFieldTagNumberToBeWrittenUponStreamFlush,
            numFlushesSoFar + 1
        );
        var outputStreamSpaceLeft = getOrCreateCodedOutputStreamHolder().getOutputStreamSpaceLeft();
        return outputStreamSpaceLeft == -1 ? -1 : outputStreamSpaceLeft - spaceNeededForRecordCreationDuringNextFlush;
    }

    /**
     * Checks if the current output stream has enough space for the required size and flushes if not.
     * This method evaluates the writable space left in the current stream. If the space is insufficient
     * for the required size, it triggers a flush operation by calling {@link #flushCommitAndResetStream(boolean)}
     * with 'false' to indicate this is not a final operation. If there is adequate space,
     * it returns a completed future with null.
     *
     * @param requiredSize The size required to write to the current stream.
     * @return CompletableFuture{@code <T>} A future that completes immediately with null if there is enough space,
     *         or completes with the future returned by flushCommitAndResetStream if a flush is needed.
     * @throws IOException if there are I/O errors when checking the stream's space or flushing.
     */
    public CompletableFuture<T> flushIfNeeded(int requiredSize) throws IOException {
        var spaceLeft = currentOutputStreamWriteableSpaceLeft();
        if (spaceLeft != -1 && spaceLeft < requiredSize) {
            return flushCommitAndResetStream(false);
        }
        return CompletableFuture.completedFuture(null);
    }

    private void writeTrafficStreamTag(int fieldNumber) throws IOException {
        getOrCreateCodedOutputStream().writeTag(
            fieldNumber,
            getWireTypeForFieldIndex(TrafficStream.getDescriptor(), fieldNumber)
        );
    }

    private void writeObservationTag(int fieldNumber) throws IOException {
        getOrCreateCodedOutputStream().writeTag(
            fieldNumber,
            getWireTypeForFieldIndex(TrafficObservation.getDescriptor(), fieldNumber)
        );
    }

    /**
     * Will write the beginning fields for a TrafficObservation after first checking if sufficient space exists in the
     * CodedOutputStream and flushing if space does not exist. This should be called before writing any observation to
     * the TrafficStream.
     */
    private void beginSubstreamObservation(
        Instant timestamp,
        int captureTagFieldNumber,
        int captureTagLengthAndContentSize
    ) throws IOException {
        final var tsContentSize = CodedOutputStreamSizeUtil.getSizeOfTimestamp(timestamp);
        final var tsTagSize = CodedOutputStream.computeInt32Size(TrafficObservation.TS_FIELD_NUMBER, tsContentSize);
        final var captureTagNoLengthSize = CodedOutputStream.computeTagSize(captureTagFieldNumber);
        final var observationContentSize = tsTagSize + tsContentSize + captureTagNoLengthSize
            + captureTagLengthAndContentSize;
        // Ensure space is available before starting an observation
        flushIfNeeded(
            CodedOutputStreamSizeUtil.bytesNeededForObservationAndClosingIndex(
                observationContentSize,
                numFlushesSoFar + 1
            )
        );
        // e.g. <pre> 2 { </pre>
        writeTrafficStreamTag(TrafficStream.SUBSTREAM_FIELD_NUMBER);
        // Write observation content length
        getOrCreateCodedOutputStream().writeUInt32NoTag(observationContentSize);
        // e.g. <pre> 1 { 1: 1234 2: 1234 } </pre>
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

    /**
     * Computes the maximum number of writable bytes for a length-delimited field within a given total available space.
     * This method takes into account the space required for encoding the length of the field itself, which might reduce
     * the writable space due to the encoding overhead.
     *
     * @param totalAvailableSpace The total available space in bytes that can be used for both the length field and the data.
     * @param requestedFieldSize The desired number of writable bytes the caller wishes to use for the data, excluding the length field.
     * @return The maximum number of bytes that can be written as data, which may be less than the requestedWritableSpace due to space
     *         taken by the length field and totalAvailableSpace. If a length delimited field is written of the returned size, and the returned
     *         length is less than requestedFieldSize, then there will be at most one byte of available space remaining. In some cases
     *         this byte is due to overestimation of lengthFieldSpace in which an optimal calculation would have returned one length
     *         greater, and in other cases it is due to the length returned being at the border of an increase in the lengthFieldSpace
     *         and thus an additional space on the return value would require two additional space to write.
     */
    public static int computeMaxLengthDelimitedFieldSizeForSpace(int totalAvailableSpace, int requestedFieldSize) {
        // A pessimistic calculation space required for the length field due to not accounting for the space of the
        // length field itself.
        // This may yield a length space one byte greater than optimal, potentially leaving at most one length delimited
        // byte
        // which the availableSpace has space for.
        final int pessimisticLengthFieldSpace = CodedOutputStream.computeUInt32SizeNoTag(totalAvailableSpace);
        int maxWriteBytesSpace = totalAvailableSpace - pessimisticLengthFieldSpace;
        return Math.min(maxWriteBytesSpace, requestedFieldSize);
    }

    private void readByteBufIntoCurrentStream(int fieldNum, ByteBuf buf) throws IOException {
        var codedOutputStream = getOrCreateCodedOutputStream();
        final int bufReadableLength = buf.readableBytes();
        if (bufReadableLength > 0) {
            // Here we are optimizing to reduce the number of internal copies and merges performed on the netty
            // ByteBuf to write to the CodedOutputStream especially in cases of Composite and Direct ByteBufs. We
            // can do this by delegating the individual ByteBuffer writes to netty which will retain the underlying
            // structure. We also need to be careful with the exact CodedOutputStream operations performed to ensure
            // we write until hitting ByteBuf/ByteBuffer writerIndex/limit and not their capacity since some
            // CodedOutputStream operations write until capacity, e.g. CodedOutputStream::writeByteBuffer
            codedOutputStream.writeTag(fieldNum, WireFormat.WIRETYPE_LENGTH_DELIMITED);
            codedOutputStream.writeUInt32NoTag(bufReadableLength);
            buf.readBytes(new ByteOutputGatheringByteChannel(codedOutputStream), bufReadableLength);
            assert buf.readableBytes() == 0 : "Expected buf bytes read but instead left "
                + buf.readableBytes()
                + " unread.";
        } else {
            codedOutputStream.writeUInt32NoTag(0);
        }
    }

    private void writeByteStringToCurrentStream(int fieldNum, String str) throws IOException {
        if (!str.isEmpty()) {
            getOrCreateCodedOutputStream().writeString(fieldNum, str);
        } else {
            getOrCreateCodedOutputStream().writeUInt32NoTag(0);
        }
    }

    /**
     * Writes a record to the stream, flushes it, and begins its closure. This method synchronously sets up
     * the closing process of the underlying stream and prepares the CodedOutputStreamHolder to return a new stream on next retrieval.
     * Each invocation writes a record to signal the current state: the final chunk if 'isFinal' is true,
     * otherwise a continuation. Returns a CompletableFuture that resolves upon the stream's closure.
     *
     * @param isFinal Indicates if this should be the final operation on the stream.
     * @return CompletableFuture{@code <T>} A future that completes when the stream is closed. Returns null if already closed or no stream exists and 'isFinal' is false.
     * @throws IOException if there are I/O errors during the operation.
     */

    @Override
    public CompletableFuture<T> flushCommitAndResetStream(boolean isFinal) throws IOException {
        if (streamHasBeenClosed || (currentCodedOutputStreamHolderOrNull == null && !isFinal)) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            CodedOutputStream currentStream = getOrCreateCodedOutputStream();
            var fieldNum = isFinal
                ? TrafficStream.NUMBEROFTHISLASTCHUNK_FIELD_NUMBER
                : TrafficStream.NUMBER_FIELD_NUMBER;
            // e.g. 3: 1
            currentStream.writeInt32(fieldNum, ++numFlushesSoFar);
            log.trace("Flushing the current CodedOutputStream for {}.{}", connectionIdString, numFlushesSoFar);
            currentStream.flush();
            assert currentStream == currentCodedOutputStreamHolderOrNull.getOutputStream() : "Expected the stream that "
                + "is being finalized to be the same stream contained by currentCodedOutputStreamHolderOrNull";
            return streamManager.closeStream(currentCodedOutputStreamHolderOrNull, numFlushesSoFar);
        } finally {
            currentCodedOutputStreamHolderOrNull = null;
            if (isFinal) {
                streamHasBeenClosed = true;
            }
        }
    }

    @Override
    public void cancelCaptureForCurrentRequest(Instant timestamp) throws IOException {
        beginSubstreamObservation(timestamp, TrafficObservation.REQUESTDROPPED_FIELD_NUMBER, 1);
        getOrCreateCodedOutputStream().writeMessage(
            TrafficObservation.REQUESTDROPPED_FIELD_NUMBER,
            RequestIntentionallyDropped.getDefaultInstance()
        );
        this.readObservationsAreWaitingForEom = false;
        this.firstLineByteLength = -1;
        this.headersByteLength = -1;
    }

    @Override
    public void addBindEvent(Instant timestamp, SocketAddress addr) throws IOException {
        // not implemented for this serializer. The v1.0 version of the replayer will ignore this type of observation
    }

    @Override
    public void addConnectEvent(Instant timestamp, SocketAddress remote, SocketAddress local) throws IOException {
        // not implemented for this serializer. The v1.0 version of the replayer will ignore this type of observation
    }

    @Override
    public void addDisconnectEvent(Instant timestamp) throws IOException {
        // not implemented for this serializer. The v1.0 version of the replayer will ignore this type of observation
    }

    @Override
    public void addCloseEvent(Instant timestamp) throws IOException {
        beginSubstreamObservation(timestamp, TrafficObservation.CLOSE_FIELD_NUMBER, 1);
        getOrCreateCodedOutputStream().writeMessage(
            TrafficObservation.CLOSE_FIELD_NUMBER,
            CloseObservation.getDefaultInstance()
        );
    }

    @Override
    public void addDeregisterEvent(Instant timestamp) throws IOException {
        // not implemented for this serializer. The v1.0 version of the replayer will ignore this type of observation
    }

    private void addStringMessage(int captureFieldNumber, int dataFieldNumber, Instant timestamp, @NonNull String str)
        throws IOException {
        int dataSize = 0;
        int lengthSize = 1;
        if (!str.isEmpty()) {
            dataSize = CodedOutputStream.computeStringSize(dataFieldNumber, str);
            lengthSize = CodedOutputStream.computeInt32SizeNoTag(dataSize);
        }
        beginSubstreamObservation(timestamp, captureFieldNumber, dataSize + lengthSize);
        // e.g. <pre> 4 { </pre>
        writeObservationTag(captureFieldNumber);
        if (dataSize > 0) {
            getOrCreateCodedOutputStream().writeInt32NoTag(dataSize);
        }
        writeByteStringToCurrentStream(dataFieldNumber, str);
    }

    private void addDataMessage(int captureFieldNumber, int dataFieldNumber, Instant timestamp, ByteBuf buf)
        throws IOException {
        int segmentFieldNumber;
        int segmentDataFieldNumber;
        if (captureFieldNumber == TrafficObservation.READ_FIELD_NUMBER) {
            segmentFieldNumber = TrafficObservation.READSEGMENT_FIELD_NUMBER;
            segmentDataFieldNumber = ReadSegmentObservation.DATA_FIELD_NUMBER;
        } else {
            segmentFieldNumber = TrafficObservation.WRITESEGMENT_FIELD_NUMBER;
            segmentDataFieldNumber = WriteSegmentObservation.DATA_FIELD_NUMBER;
        }

        // The message bytes here are not optimizing for space and instead are calculated on the worst case estimate of
        // the potentially required bytes for simplicity. This could leave ~5 bytes of unused space in the
        // CodedOutputStream
        // when considering the case of a message that does not need segments or for the case of a smaller segment
        // created
        // from a much larger message
        final int messageAndOverheadBytesLeft = CodedOutputStreamSizeUtil.maxBytesNeededForASegmentedObservation(
            timestamp,
            segmentFieldNumber,
            segmentDataFieldNumber,
            buf
        );
        final int dataSize = CodedOutputStreamSizeUtil.computeByteBufRemainingSizeNoTag(buf);
        final int trafficStreamOverhead = messageAndOverheadBytesLeft - dataSize;

        // Writing one data byte requires two bytes to account for length byte
        final int maxBytesNeededForOneSegmentWithOneDataByteWithLengthByte = trafficStreamOverhead + 2;

        flushIfNeeded(maxBytesNeededForOneSegmentWithOneDataByteWithLengthByte);
        var spaceLeft = currentOutputStreamWriteableSpaceLeft();

        var bufToRead = buf.duplicate();
        // If our message is empty or can fit in the current CodedOutputStream no chunking is needed, and we can
        // continue
        if (bufToRead.readableBytes() == 0 || spaceLeft == -1 || messageAndOverheadBytesLeft <= spaceLeft) {
            int minExpectedSpaceAfterObservation = spaceLeft - messageAndOverheadBytesLeft;
            addSubstreamMessage(captureFieldNumber, dataFieldNumber, timestamp, bufToRead);
            observationSizeSanityCheck(minExpectedSpaceAfterObservation, captureFieldNumber);
        } else {
            while (bufToRead.readableBytes() > 0) {
                spaceLeft = currentOutputStreamWriteableSpaceLeft();
                var bytesToRead = computeMaxLengthDelimitedFieldSizeForSpace(
                    spaceLeft - trafficStreamOverhead,
                    bufToRead.readableBytes()
                );
                if (bytesToRead <= 0) {
                    throw new IllegalStateException("Stream space is not allowing forward progress on byteBuf reading");
                }
                var bufSliceToRead = bufToRead.readSlice(bytesToRead);
                addSubstreamMessage(segmentFieldNumber, segmentDataFieldNumber, timestamp, bufSliceToRead);
                if (bufToRead.readableBytes() > 0) {
                    flushIfNeeded(maxBytesNeededForOneSegmentWithOneDataByteWithLengthByte);
                }
            }
            writeEndOfSegmentMessage(timestamp);
        }
    }

    private void addSubstreamMessage(
        int captureFieldNumber,
        int dataFieldNumber,
        int dataCountFieldNumber,
        int dataCount,
        Instant timestamp,
        ByteBuf byteBuf
    ) throws IOException {
        int dataBytesSize = 0;
        int dataTagSize = 0;
        int dataSize = dataBytesSize + dataTagSize;
        int segmentCountSize = 0;
        int captureClosureLength = 1;
        CodedOutputStream codedOutputStream = getOrCreateCodedOutputStream();
        if (dataCountFieldNumber > 0) {
            segmentCountSize = CodedOutputStream.computeInt32Size(dataCountFieldNumber, dataCount);
        }
        if (byteBuf.readableBytes() > 0) {
            dataSize = CodedOutputStreamSizeUtil.computeByteBufRemainingSize(dataFieldNumber, byteBuf);
            captureClosureLength = CodedOutputStream.computeInt32SizeNoTag(dataSize + segmentCountSize);
        }
        beginSubstreamObservation(timestamp, captureFieldNumber, captureClosureLength + dataSize + segmentCountSize);
        // e.g. <pre> 4 {  </pre>
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
        readByteBufIntoCurrentStream(dataFieldNumber, byteBuf);
        if (captureFieldNumber == TrafficObservation.READ_FIELD_NUMBER
            || captureFieldNumber == TrafficObservation.READSEGMENT_FIELD_NUMBER) {
            this.readObservationsAreWaitingForEom = true;
        }
    }

    private void addSubstreamMessage(int captureFieldNumber, int dataFieldNumber, Instant timestamp, ByteBuf byteBuf)
        throws IOException {
        addSubstreamMessage(captureFieldNumber, dataFieldNumber, 0, 0, timestamp, byteBuf);
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
        // not implemented for this serializer. The v1.0 version of the replayer will ignore this type of observation
    }

    @Override
    public void addChannelRegisteredEvent(Instant timestamp) throws IOException {
        // not implemented for this serializer. The v1.0 version of the replayer will ignore this type of observation
    }

    @Override
    public void addChannelUnregisteredEvent(Instant timestamp) throws IOException {
        // not implemented for this serializer. The v1.0 version of the replayer will ignore this type of observation
    }

    @Override
    public void addChannelActiveEvent(Instant timestamp) throws IOException {
        // not implemented for this serializer. The v1.0 version of the replayer will ignore this type of observation
    }

    @Override
    public void addChannelInactiveEvent(Instant timestamp) throws IOException {
        // not implemented for this serializer. The v1.0 version of the replayer will ignore this type of observation
    }

    @Override
    public void addChannelReadEvent(Instant timestamp) throws IOException {
        // not implemented for this serializer. The v1.0 version of the replayer will ignore this type of observation
    }

    @Override
    public void addChannelReadCompleteEvent(Instant timestamp) throws IOException {
        // not implemented for this serializer. The v1.0 version of the replayer will ignore this type of observation
    }

    @Override
    public void addUserEventTriggeredEvent(Instant timestamp) throws IOException {
        // not implemented for this serializer. The v1.0 version of the replayer will ignore this type of observation
    }

    @Override
    public void addChannelWritabilityChangedEvent(Instant timestamp) throws IOException {
        // not implemented for this serializer. The v1.0 version of the replayer will ignore this type of observation
    }

    @Override
    public void addExceptionCaughtEvent(Instant timestamp, Throwable t) throws IOException {
        addStringMessage(
            TrafficObservation.CONNECTIONEXCEPTION_FIELD_NUMBER,
            ConnectionExceptionObservation.MESSAGE_FIELD_NUMBER,
            timestamp,
            t.getMessage()
        );
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
    public void commitEndOfHttpMessageIndicator(Instant timestamp) throws IOException {
        writeEndOfHttpMessage(timestamp);
        this.readObservationsAreWaitingForEom = false;
        ++this.eomsSoFar;
        this.firstLineByteLength = -1;
        this.headersByteLength = -1;
    }

    private void writeEndOfHttpMessage(Instant timestamp) throws IOException {
        int eomPairSize = CodedOutputStream.computeInt32Size(
            EndOfMessageIndication.FIRSTLINEBYTELENGTH_FIELD_NUMBER,
            firstLineByteLength
        ) + CodedOutputStream.computeInt32Size(
            EndOfMessageIndication.HEADERSBYTELENGTH_FIELD_NUMBER,
            headersByteLength
        );
        int eomDataSize = eomPairSize + CodedOutputStream.computeInt32SizeNoTag(eomPairSize);
        beginSubstreamObservation(timestamp, TrafficObservation.ENDOFMESSAGEINDICATOR_FIELD_NUMBER, eomDataSize);
        // e.g. <pre> 15 { </pre>
        writeObservationTag(TrafficObservation.ENDOFMESSAGEINDICATOR_FIELD_NUMBER);
        getOrCreateCodedOutputStream().writeUInt32NoTag(eomPairSize);
        getOrCreateCodedOutputStream().writeInt32(
            EndOfMessageIndication.FIRSTLINEBYTELENGTH_FIELD_NUMBER,
            firstLineByteLength
        );
        getOrCreateCodedOutputStream().writeInt32(
            EndOfMessageIndication.HEADERSBYTELENGTH_FIELD_NUMBER,
            headersByteLength
        );
    }

    private void writeEndOfSegmentMessage(Instant timestamp) throws IOException {
        beginSubstreamObservation(timestamp, TrafficObservation.SEGMENTEND_FIELD_NUMBER, 1);
        getOrCreateCodedOutputStream().writeMessage(
            TrafficObservation.SEGMENTEND_FIELD_NUMBER,
            EndOfSegmentsIndication.getDefaultInstance()
        );
    }

    private void observationSizeSanityCheck(int minExpectedSpaceAfterObservation, int fieldNumber) throws IOException {
        int actualRemainingSpace = currentOutputStreamWriteableSpaceLeft();
        if (actualRemainingSpace != -1
            && (actualRemainingSpace < minExpectedSpaceAfterObservation || minExpectedSpaceAfterObservation < 0)) {
            log.warn(
                "Writing a substream (capture type: {}) for Traffic Stream: {} left {} bytes in the CodedOutputStream but we calculated "
                    + "at least {} bytes remaining, this should be investigated",
                fieldNumber,
                connectionIdString + "." + (numFlushesSoFar + 1),
                actualRemainingSpace,
                minExpectedSpaceAfterObservation
            );
        }
    }

    private static class ByteOutputGatheringByteChannel implements GatheringByteChannel {
        final ByteOutput byteOutput;

        public ByteOutputGatheringByteChannel(ByteOutput byteOutput) {
            this.byteOutput = byteOutput;
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            var bytesToWrite = src.remaining();
            byteOutput.write(src);
            return bytesToWrite - src.remaining();
        }

        @Override
        public long write(ByteBuffer[] srcs, int offset, int length) throws UncheckedIOException {
            return IntStream.range(offset, offset + length).mapToLong(i -> {
                try {
                    return write(srcs[i]);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }).sum();
        }

        @Override
        public long write(ByteBuffer[] srcs) throws IOException {
            return write(srcs, 0, srcs.length);
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public void close() {
            // No resources to close
        }
    }
}
