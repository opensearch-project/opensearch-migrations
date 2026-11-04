package org.opensearch.migrations.bulkload.common;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.NoSuchElementException;

import org.opensearch.migrations.bulkload.common.bulk.BulkNdjson;

import reactor.core.publisher.Flux;

/**
 * Splits the validated JSON bytes produced by BulkNdjson without materializing strings or a tree.
 * Copies escaped byte ranges directly into request parts, preserving UTF-8 and escape boundaries.
 * Additional live state is one bounded request plus the container stack, independent of token size.
 * JSON syntax validation belongs to BulkNdjson; duplicate keys are rejected by the assembly script.
 */
final class JsonDocumentChunks implements Iterator<byte[]>, AutoCloseable {
    private static final int MAX_EVENTS = 512;
    private static final byte[] PREFIX = prefix();
    private static final byte[] EVENTS = bytes(",\"events\":[");
    private static final byte[] SUFFIX = bytes("]}}}");
    private static final byte[] TEXT_SUFFIX = bytes("\"]");

    private byte[] document;
    private final long limit;
    private final Deque<Container> containers = new ArrayDeque<>();
    private int offset;
    private byte[] pending;
    private String textKind;
    private boolean number;
    private boolean endText;
    private boolean finished;
    private long sequence;

    static Flux<OperationSplitter.Part> split(BulkNdjson.EncodedOperation operation, long limit) {
        return Flux.generate(() -> new JsonDocumentChunks(operation.body(), limit), (cursor, sink) -> {
            if (cursor.hasNext()) {
                sink.next(new OperationSplitter.Part(cursor.chunksProduced(), cursor.next()));
            } else {
                sink.complete();
            }
            return cursor;
        }, JsonDocumentChunks::close);
    }

    JsonDocumentChunks(byte[] document, long limit) {
        if (PREFIX.length + EVENTS.length + SUFFIX.length + 33 > limit) {
            throw new IllegalArgumentException("Request limit is too small for the document assembly script");
        }
        this.document = document;
        this.limit = limit;
        if (document.length >= 3 && document[0] == (byte) 0xEF && document[1] == (byte) 0xBB && document[2] == (byte) 0xBF) {
            offset = 3; // Jackson's raw-source validation accepts an initial UTF-8 BOM.
        }
        skipWhitespace();
        if (offset == document.length || document[offset++] != '{') {
            throw new IllegalArgumentException("A staged document must be a JSON object");
        }
        containers.push(new Container(true));
        pending = bytes("[\"object\"]");
    }

    long chunksProduced() {
        return sequence;
    }

    @Override
    public boolean hasNext() {
        loadEvent();
        return pending != null || textKind != null;
    }

    @Override
    public byte[] next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        var out = new ByteArrayOutputStream();
        out.writeBytes(PREFIX);
        out.writeBytes(bytes(Long.toString(sequence)));
        out.writeBytes(EVENTS);
        int count = 0;
        while (count < MAX_EVENTS) {
            loadEvent();
            long available = limit - out.size() - SUFFIX.length - (count == 0 ? 0 : 1);
            byte[] event = takeEvent(available);
            if (event == null) {
                break;
            }
            if (count++ != 0) {
                out.write(',');
            }
            out.writeBytes(event);
        }
        if (count == 0) {
            throw new IllegalArgumentException("Request limit is too small for a JSON scalar or script envelope");
        }
        out.writeBytes(SUFFIX);
        sequence++;
        return out.toByteArray();
    }

    private byte[] takeEvent(long available) {
        if (pending != null) {
            if (pending.length > available) {
                return null;
            }
            var event = pending;
            pending = null;
            return event;
        }
        return textKind == null ? null : takeText(available);
    }

    private void loadEvent() {
        if (pending != null || textKind != null || finished) {
            return;
        }
        if (endText) {
            endText = false;
            pending = bytes("[\"end\"]");
            return;
        }
        // The input has already passed JSON validation. Only container/key position is needed here.
        while (pending == null && textKind == null) {
            skipWhitespace();
            if (offset == document.length) {
                throw new IllegalArgumentException("Incomplete staged JSON object");
            }
            readEvent();
        }
    }

    private void readEvent() {
        switch (document[offset++]) {
            case '{', '[' -> {
                boolean object = document[offset - 1] == '{';
                containers.push(new Container(object));
                pending = bytes(object ? "[\"object\"]" : "[\"array\"]");
            }
            case '}', ']' -> closeContainer();
            case ':' -> containers.peek().key = false;
            case ',' -> containers.peek().key = containers.peek().object;
            case '"' -> {
                textKind = containers.peek().key ? "key" : "string";
                number = false;
            }
            case 't' -> scalar("true");
            case 'f' -> scalar("false");
            case 'n' -> scalar("null");
            default -> {
                offset--;
                if (!numberByte(document[offset])) {
                    throw new IllegalArgumentException("Invalid staged JSON token at byte " + offset);
                }
                textKind = "number";
                number = true;
            }
        }
    }

    private void closeContainer() {
        var container = containers.pop();
        if (container.object != (document[offset - 1] == '}')) {
            throw new IllegalArgumentException("Mismatched JSON container");
        }
        pending = bytes("[\"end\"]");
        if (containers.isEmpty()) {
            skipWhitespace();
            if (offset != document.length) {
                throw new IllegalArgumentException("A staged document must contain exactly one JSON object");
            }
            finished = true;
        }
    }

    private void scalar(String literal) {
        int start = offset - 1;
        for (int i = 0; i < literal.length(); i++) {
            if (start + i >= document.length || document[start + i] != literal.charAt(i)) {
                throw new IllegalArgumentException("Invalid staged JSON scalar");
            }
        }
        offset = start + literal.length();
        pending = bytes("[\"value\"," + literal + "]");
    }

    /** Keep the existing escaping; every fragment is itself a valid JSON string. */
    private byte[] takeText(long available) {
        byte[] header = bytes("[\"" + textKind + "\",\"");
        long capacity = available - header.length - TEXT_SUFFIX.length;
        if (capacity < 0) {
            return null;
        }
        int start = offset;
        boolean complete = advanceText(start, capacity);
        int length = offset - start;
        if (length == 0 && !complete) {
            return null;
        }
        var event = new byte[header.length + length + TEXT_SUFFIX.length];
        System.arraycopy(header, 0, event, 0, header.length);
        System.arraycopy(document, start, event, header.length, length);
        System.arraycopy(TEXT_SUFFIX, 0, event, header.length + length, TEXT_SUFFIX.length);
        if (complete) {
            if (!number) {
                offset++; // closing quote
            }
            endText = !textKind.startsWith("key");
            textKind = null;
        } else if (!textKind.endsWith("+")) {
            textKind += "+";
        }
        return event;
    }

    private boolean advanceText(int start, long capacity) {
        while (offset < document.length) {
            if (number ? !numberByte(document[offset]) : document[offset] == '"') {
                return true;
            }
            int width = number ? 1 : stringUnitWidth(offset);
            if (offset - start + width > capacity) {
                return false;
            }
            offset += width;
        }
        throw new IllegalArgumentException("Incomplete staged JSON token");
    }

    private int stringUnitWidth(int start) {
        int first = Byte.toUnsignedInt(document[start]);
        if (first == '\\') {
            if (document[start + 1] != 'u') {
                return 2;
            }
            // An escaped UTF-16 surrogate pair must stay in one fragment too.
            if (Character.isHighSurrogate(hexCharacter(start + 2)) && start + 12 <= document.length
                && document[start + 6] == '\\' && document[start + 7] == 'u'
                && Character.isLowSurrogate(hexCharacter(start + 8))) {
                return 12;
            }
            return 6;
        }
        if (first < 0x80) {
            return 1;
        }
        if (first < 0xE0) {
            return 2;
        }
        return first < 0xF0 ? 3 : 4;
    }

    private char hexCharacter(int start) {
        int value = 0;
        for (int i = start; i < start + 4; i++) {
            value = (value << 4) | Character.digit((char) document[i], 16);
        }
        return (char) value;
    }

    private static boolean numberByte(byte value) {
        return (value >= '0' && value <= '9') || value == '-' || value == '+'
            || value == '.' || value == 'e' || value == 'E';
    }

    private void skipWhitespace() {
        while (offset < document.length && Byte.toUnsignedInt(document[offset]) <= ' ') {
            offset++;
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] prefix() {
        try (var stream = JsonDocumentChunks.class.getResourceAsStream("/large-documents/assemble.painless")) {
            if (stream == null) {
                throw new IllegalStateException("Missing document assembly script");
            }
            var script = ObjectMapperFactory.createDefaultMapper().writeValueAsString(
                new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            return bytes("{\"script\":{\"lang\":\"painless\",\"source\":" + script + ",\"params\":{\"seq\":");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        document = null;
        pending = null;
        textKind = null;
        containers.clear();
        finished = true;
    }

    private static final class Container {
        final boolean object;
        boolean key;

        Container(boolean object) {
            this.object = object;
            key = object;
        }
    }
}
