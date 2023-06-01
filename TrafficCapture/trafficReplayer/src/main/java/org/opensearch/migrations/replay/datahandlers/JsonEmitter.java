package org.opensearch.migrations.replay.datahandlers;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.netty.util.ResourceLeakDetector;
import io.netty.util.ResourceLeakDetectorFactory;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.Stack;
import java.util.function.Supplier;

@Slf4j
public class JsonEmitter {

    public final static int NUM_SEGMENT_THRESHOLD = 256;

    @Getter
    public static class PartialOutputAndContinuation {
        public final ByteBuf partialSerializedContents;
        public final Supplier<PartialOutputAndContinuation> nextSupplier;

        public PartialOutputAndContinuation(ByteBuf partialSerializedContents,
                                            Supplier<PartialOutputAndContinuation> nextSupplier) {
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
    private static class LevelContext {
        public final Iterator<Object> iterator;
        public final Runnable onPopContinuation;

        public LevelContext(Iterator<Object> iterator, Runnable onPopContinuation) {
            this.iterator = iterator;
            this.onPopContinuation = onPopContinuation;
        }
    }

    static class ImmediateByteBufOutputStream extends OutputStream {
        @Getter
        CompositeByteBuf compositeByteBuf;
        ResourceLeakDetector<ByteBuf> leakDetector = ResourceLeakDetectorFactory.instance()
                .newResourceLeakDetector(ByteBuf.class);

        public ImmediateByteBufOutputStream() {
            compositeByteBuf = ByteBufAllocator.DEFAULT.compositeBuffer();
        }

        @Override
        public void write(int b) throws IOException {
            var byteBuf = ByteBufAllocator.DEFAULT.buffer(1);
            write(byteBuf.writeByte(b));
        }

        @Override
        public void write(byte[] buff, int offset, int len) throws IOException {
            var byteBuf = ByteBufAllocator.DEFAULT.buffer(len - offset);
            write(byteBuf.writeBytes(buff, offset, len));
        }

        private void write(ByteBuf byteBuf) {
            leakDetector.track(byteBuf);
            compositeByteBuf.addComponents(true, byteBuf);
        }

        @Override
        public void close() throws IOException {
            compositeByteBuf.release();
            super.close();
        }

        /**
         * Transfers the retained ByteBuf.  Caller is responsible for release().
         *
         * @return
         */
        public ByteBuf recycleByteBufRetained() {
            var rval = compositeByteBuf;
            compositeByteBuf = ByteBufAllocator.DEFAULT.compositeBuffer(rval.maxNumComponents());
            return rval;
        }
    }

    private JsonGenerator jsonGenerator;
    private ImmediateByteBufOutputStream outputStream;
    private ObjectMapper objectMapper;
    private Stack<LevelContext> cursorStack;

    @SneakyThrows
    public JsonEmitter() {
        outputStream = new ImmediateByteBufOutputStream();
        jsonGenerator = new JsonFactory().createGenerator(outputStream, JsonEncoding.UTF8);
        objectMapper = new ObjectMapper();
        cursorStack = new Stack<>();
    }

    public PartialOutputAndContinuation getChunkAndContinuations(Object object, int minBytes) throws IOException {
        log.trace("getChunkAndContinuations(..., "+minBytes+")");
        return getChunkAndContinuationsHelper(walkTreeWithContinuations(object), minBytes);
    }
    private PartialOutputAndContinuation getChunkAndContinuationsHelper(FragmentSupplier nextSupplier, int minBytes) {
        var compositeByteBuf = outputStream.compositeByteBuf;
        if (compositeByteBuf.numComponents() > NUM_SEGMENT_THRESHOLD ||
                compositeByteBuf.readableBytes() > minBytes) {
            var byteBuf = outputStream.recycleByteBufRetained();
            log.debug("getChunkAndContinuationsHelper->" + byteBuf.readableBytes() + " bytes + continuation");
            return new PartialOutputAndContinuation(byteBuf,
                    () -> getChunkAndContinuationsHelper(nextSupplier, minBytes));
        }
        if (nextSupplier == null) {
            try {
                flush();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            var byteBuf = outputStream.recycleByteBufRetained();
            log.debug("getChunkAndContinuationsHelper->" + byteBuf.readableBytes() + " bytes + null");
            return new PartialOutputAndContinuation(byteBuf, null);
        }
        log.trace("getChunkAndContinuationsHelper->recursing with " + outputStream.compositeByteBuf.readableBytes() +
                " written bytes buffered");
        return getChunkAndContinuationsHelper(nextSupplier.supplier.get(), minBytes);
    }

    private FragmentSupplier processStack() {
        if (cursorStack.empty()) {
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

    private void push(Iterator it, Runnable onPopContinuation) {
        cursorStack.push(new LevelContext(it, onPopContinuation));
    }

    public FragmentSupplier walkTreeWithContinuations(Object o) {
        log.trace("walkTree... "+o);
        if (o instanceof Map.Entry) {
            var kvp = (Map.Entry<String,Object>) o;
            writeFieldName(kvp.getKey());
            return walkTreeWithContinuations(kvp.getValue());
        } else if (o instanceof Map) {
            writeStartObject();
            push(((Map<String,Object>) o).entrySet().iterator(), () -> writeEndObject());
        } else if (o instanceof ObjectNode) {
            writeStartObject();
            push(((ObjectNode) o).fields(), () -> writeEndObject());
        } else if (o.getClass().isArray()) {
            writeStartArray();
            push(Arrays.stream((Object[]) o).iterator(), () -> writeEndArray());
        } else if (o instanceof ArrayNode) {
            writeStartArray();
            push(((ArrayNode) o).iterator(), () -> writeEndArray());
        } else {
            writeValue(o);
        }
        return new FragmentSupplier(() -> processStack());
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
    public void writeFieldName(String fieldName) {
        jsonGenerator.writeFieldName(fieldName);
    }

    @SneakyThrows
    public void writeValue(Object s) {
        objectMapper.writeValue(jsonGenerator, s);
    }

    public void flush() throws IOException {
        jsonGenerator.flush();
        outputStream.flush();
    }
}
