package org.opensearch.migrations.replay.datahandlers;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import lombok.Getter;
import lombok.Lombok;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

/**
 * This class writes a JSON object to a series of ByteBufs using Jackson's JsonGenerator.
 * Those ByteBufs are returned by a generation function returned by getChunksAndContinuations.
 * This block-based generator fits in well with our streaming pipeline, allowing us to minimize
 * the memory load rather than expanding the working set for a large textual stream.
 */
@Slf4j
public class JsonEmitter implements AutoCloseable {

    public static final int NUM_SEGMENT_THRESHOLD = 256;

    @Getter
    public static class PartialOutputAndContinuation {
        public final ByteBuf partialSerializedContents;
        public final Supplier<PartialOutputAndContinuation> nextSupplier;

        public PartialOutputAndContinuation(
            ByteBuf partialSerializedContents,
            Supplier<PartialOutputAndContinuation> nextSupplier
        ) {
            this.partialSerializedContents = partialSerializedContents;
            this.nextSupplier = nextSupplier;
        }
    }

    @Getter
    private static class FragmentSupplier {
        public final Supplier<FragmentSupplier> supplier;

        public FragmentSupplier(Supplier<FragmentSupplier> supplier) {
            this.supplier = supplier;
        }
    }

    private static class LevelContext<T> {
        public final Iterator<T> iterator;
        public final Runnable onPopContinuation;

        public LevelContext(Iterator<T> iterator, Runnable onPopContinuation) {
            this.iterator = iterator;
            this.onPopContinuation = onPopContinuation;
        }
    }

    static class ChunkingByteBufOutputStream extends OutputStream {
        private final ByteBufAllocator byteBufAllocator;
        @Getter
        CompositeByteBuf compositeByteBuf;

        public ChunkingByteBufOutputStream(ByteBufAllocator byteBufAllocator) {
            this.byteBufAllocator = byteBufAllocator;
            compositeByteBuf = byteBufAllocator.compositeBuffer();
        }

        @Override
        public void write(int b) throws IOException {
            var byteBuf = byteBufAllocator.buffer(1);
            write(byteBuf.writeByte(b));
        }

        @Override
        public void write(byte[] buff, int offset, int len) throws IOException {
            var byteBuf = byteBufAllocator.buffer(len - offset);
            write(byteBuf.writeBytes(buff, offset, len));
        }

        private void write(ByteBuf byteBuf) {
            compositeByteBuf.addComponents(true, byteBuf);
        }

        @Override
        public void close() {
            compositeByteBuf.release();
            try {
                super.close();
            } catch (IOException e) {
                throw new IllegalStateException("Expected OutputStream::close() to be empty as per docs in Java 11");
            }
        }

        /**
         * Transfers the retained ByteBuf.  Caller is responsible for release().
         *
         * @return
         */
        public ByteBuf recycleByteBufRetained() {
            var rval = compositeByteBuf;
            compositeByteBuf = byteBufAllocator.compositeBuffer(rval.maxNumComponents());
            return rval;
        }
    }

    private final JsonGenerator jsonGenerator;
    private final ChunkingByteBufOutputStream outputStream;
    private final ObjectMapper objectMapper;
    private final Deque<LevelContext<? extends Object>> cursorStack;

    @SneakyThrows
    public JsonEmitter(ByteBufAllocator byteBufAllocator) {
        outputStream = new ChunkingByteBufOutputStream(byteBufAllocator);
        jsonGenerator = new JsonFactory().createGenerator(outputStream, JsonEncoding.UTF8);
        objectMapper = new ObjectMapper();
        cursorStack = new ArrayDeque<>();
    }

    @Override
    public void close() {
        outputStream.close();
    }

    /**
     * This returns a ByteBuf block of serialized data plus a recursive generation function (Supplier)
     * for the next block and continuation.  The size of the ByteBuf that will be returned can be
     * controlled by minBytes and the NUM_SEGMENT_THRESHOLD value.  Once those watermarks are reached,
     * the current CompositeByteBuffer that is consuming the serialized output will be returned along
     * with the continuation.  Notice that minBytes and NUM_SEGMENT_THRESHOLD are generally lower
     * bounds for the sizes of the ByteBufs, remaining true for all buffers returned aside from the last.
     *
     *
     * @param object
     * @param minBytes
     * @return
     * @throws IOException
     */
    public PartialOutputAndContinuation getChunkAndContinuations(Object object, int minBytes) throws IOException {
        log.trace("getChunkAndContinuations(..., " + minBytes + ")");
        return getChunkAndContinuationsHelper(walkTreeWithContinuations(object), minBytes);
    }

    /**
     * This is a helper function to rotate the internal buffers out to the calling context that
     * will be consuming the data that has been written.  It is also responsible for threading
     * the PartialOutputAndContinuation supplier until there's no more data to serialize.
     *
     * Notice that this function is effectively wrapping an inner supplier (FragmentSupplier)
     * that is performing side effects and supplying a recursive continuation with an outer
     * supplier (PartialOutputAndContinuation) that is taking the side effects, packaging them
     * up for the external calling context, and resetting the internal state for the next
     * round of processing.
     *
     * @param nextFragmentSupplier
     * @param minBytes
     * @return
     */
    private PartialOutputAndContinuation getChunkAndContinuationsHelper(
        FragmentSupplier nextFragmentSupplier,
        int minBytes
    ) {
        var compositeByteBuf = outputStream.compositeByteBuf;
        if (compositeByteBuf.numComponents() > NUM_SEGMENT_THRESHOLD || compositeByteBuf.readableBytes() > minBytes) {
            var byteBuf = outputStream.recycleByteBufRetained();
            log.debug("getChunkAndContinuationsHelper->" + byteBuf.readableBytes() + " bytes + continuation");
            return new PartialOutputAndContinuation(
                byteBuf,
                () -> getChunkAndContinuationsHelper(nextFragmentSupplier, minBytes)
            );
        }
        if (nextFragmentSupplier == null) {
            try {
                flush();
            } catch (IOException e) {
                throw Lombok.sneakyThrow(e);
            }
            var byteBuf = outputStream.recycleByteBufRetained();
            log.debug("getChunkAndContinuationsHelper->" + byteBuf.readableBytes() + " bytes + null");
            return new PartialOutputAndContinuation(byteBuf, null);
        }
        log.trace(
            "getChunkAndContinuationsHelper->recursing with "
                + outputStream.compositeByteBuf.readableBytes()
                + " written bytes buffered"
        );
        return getChunkAndContinuationsHelper(nextFragmentSupplier.supplier.get(), minBytes);
    }

    private FragmentSupplier processStack() {
        if (cursorStack.isEmpty()) {
            return null;
        }
        var currentCursor = cursorStack.peek();
        if (currentCursor.iterator.hasNext()) {
            var nextVal = currentCursor.iterator.next();
            return walkTreeWithContinuations(nextVal);
        } else {
            cursorStack.pop().onPopContinuation.run();
        }
        return processStack();
    }

    private void push(Iterator<? extends Object> it, Runnable onPopContinuation) {
        cursorStack.push(new LevelContext<>(it, onPopContinuation));
    }

    /**
     * This maintains the json stack of elements encountered so far, along with a continuation
     * to run when the item is popped from the stack.  Maintaining that stack outside of the
     * callstack allows this to remain recursive, but fully reentrant.
     *
     * This function will be called repeatedly by processStack() so that additional array and
     * object items will be processed after their prior siblings children have been fully
     * emitted.
     *
     * Notice that the return statement for this function wraps processStack() in a
     * continuation, creating the reentrant point for callers.
     * @param o
     * @return
     */
    private FragmentSupplier walkTreeWithContinuations(Object o) {
        log.trace("walkTree... " + o);
        if (o instanceof Map.Entry) {
            var kvp = (Map.Entry<String, Object>) o;
            writeFieldName(kvp.getKey());
            return walkTreeWithContinuations(kvp.getValue());
        } else if (o instanceof Map) {
            writeStartObject();
            push(((Map<String, Object>) o).entrySet().iterator(), this::writeEndObject);
        } else if (o instanceof ObjectNode) {
            writeStartObject();
            push(((ObjectNode) o).properties().iterator(), this::writeEndObject);
        } else if (o.getClass().isArray()) {
            writeStartArray();
            push(Arrays.stream((Object[]) o).iterator(), this::writeEndArray);
        } else if (o instanceof ArrayNode) {
            writeStartArray();
            push(((ArrayNode) o).iterator(), this::writeEndArray);
        } else {
            writeValue(o);
        }
        return new FragmentSupplier(this::processStack);
    }

    @SneakyThrows
    private void writeStartArray() {
        jsonGenerator.writeStartArray();
    }

    @SneakyThrows
    private void writeEndArray() {
        jsonGenerator.writeEndArray();
    }

    @SneakyThrows
    private void writeEndObject() {
        jsonGenerator.writeEndObject();
    }

    @SneakyThrows
    private void writeStartObject() {
        jsonGenerator.writeStartObject();
    }

    @SneakyThrows
    private void writeFieldName(String fieldName) {
        jsonGenerator.writeFieldName(fieldName);
    }

    @SneakyThrows
    private void writeValue(Object s) {
        objectMapper.writeValue(jsonGenerator, s);
    }

    private void flush() throws IOException {
        jsonGenerator.flush();
        outputStream.flush();
    }
}
