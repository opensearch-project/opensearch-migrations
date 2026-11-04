package org.opensearch.migrations.bulkload.common;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Streams a JSON object as bounded update requests. Container events avoid sending paths: even a
 * field name, string, or enclosing path larger than a request can be assembled across requests.
 * The input is the already transformed, encoded document, never the snapshot source.
 */
final class JsonDocumentChunks implements Iterator<byte[]>, AutoCloseable {
    private static final ObjectMapper MAPPER = ObjectMapperFactory.createDefaultMapper();
    // Bound Painless loop work independently of the byte limit.
    private static final int MAX_EVENTS = 512;
    private static final byte[] SUFFIX = "]}}}".getBytes(StandardCharsets.UTF_8);
    private static final String SCRIPT = loadScript();

    private final JsonParser parser;
    private final long limit;
    private final Deque<byte[]> pending = new ArrayDeque<>();
    private String text;
    private String textKind;
    private int textOffset;
    private int textCodePoints;
    private boolean endText;
    private boolean finished;
    private long sequence;

    JsonDocumentChunks(byte[] document, long limit) throws IOException {
        this.limit = limit;
        if (prefix(0).length + SUFFIX.length + 32 > limit) {
            throw new IllegalArgumentException("Request limit is too small for the document assembly script");
        }
        parser = MAPPER.getFactory().createParser(document);
        parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        try {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalArgumentException("A staged document must be a JSON object");
            }
            pending.add(event("object"));
        } catch (Exception e) {
            parser.close();
            throw e;
        }
    }

    long chunksProduced() {
        return sequence;
    }

    @Override
    public boolean hasNext() {
        try {
            loadEvent();
            return !pending.isEmpty() || text != null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public byte[] next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        try {
            var out = new ByteArrayOutputStream();
            out.write(prefix(sequence));
            int count = 0;
            while (count < MAX_EVENTS) {
                loadEvent();
                long available = limit - out.size() - SUFFIX.length - (count == 0 ? 0 : 1);
                byte[] next;
                if (!pending.isEmpty()) {
                    next = pending.peek();
                    if (next.length > available) {
                        break;
                    }
                    pending.remove();
                } else if (text != null) {
                    next = takeText(available);
                    if (next == null) {
                        break;
                    }
                } else {
                    break;
                }
                if (count++ != 0) {
                    out.write(',');
                }
                out.write(next);
            }
            if (count == 0) {
                throw new IllegalArgumentException("Request limit is too small for a JSON scalar or script envelope");
            }
            out.write(SUFFIX);
            sequence++;
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void loadEvent() throws IOException {
        if (!pending.isEmpty() || text != null || finished) {
            return;
        }
        if (endText) {
            endText = false;
            pending.add(event("end"));
            return;
        }
        var token = parser.nextToken();
        if (token == null) {
            finished = true;
            return;
        }
        switch (token) {
            case START_OBJECT -> pending.add(event("object"));
            case START_ARRAY -> pending.add(event("array"));
            case END_OBJECT, END_ARRAY -> {
                pending.add(event("end"));
                if (parser.getParsingContext().inRoot()) {
                    if (parser.nextToken() != null) {
                        throw new IllegalArgumentException("A staged document must contain exactly one JSON object");
                    }
                    finished = true;
                }
            }
            case FIELD_NAME, VALUE_STRING, VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> {
                text = parser.getText();
                textOffset = 0;
                textCodePoints = text.codePointCount(0, text.length());
                textKind = token == JsonToken.FIELD_NAME ? "key" : token == JsonToken.VALUE_STRING ? "string" : "number";
                endText = token != JsonToken.FIELD_NAME;
            }
            case VALUE_TRUE -> pending.add(event("value", true));
            case VALUE_FALSE -> pending.add(event("value", false));
            case VALUE_NULL -> pending.add(event("value", (Object) null));
            default -> throw new IllegalArgumentException("Unsupported JSON token: " + token);
        }
    }

    /** Find a fitting fragment using its actual escaped UTF-8 bytes; never bisect a surrogate pair. */
    private byte[] takeText(long available) throws IOException {
        int low = 0;
        int high = Math.min(textCodePoints,
            (int) Math.min(Integer.MAX_VALUE, Math.max(0, available)));
        byte[] best = event(textKind, "");
        if (best.length > available) {
            return null;
        }
        int bestEnd = textOffset;
        int bestCount = 0;
        while (low <= high) {
            int mid = low + (high - low) / 2;
            int end = text.offsetByCodePoints(textOffset, mid);
            byte[] candidate = event(textKind, text.substring(textOffset, end));
            if (candidate.length <= available) {
                best = candidate;
                bestEnd = end;
                bestCount = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        if (bestEnd == textOffset && textOffset < text.length()) {
            return null;
        }
        textOffset = bestEnd;
        textCodePoints -= bestCount;
        textKind = textKind.startsWith("key") ? "key+" : textKind.startsWith("number") ? "number+" : "string+";
        if (textOffset == text.length()) {
            text = null;
        }
        return best;
    }

    private static byte[] event(String kind, Object... value) throws IOException {
        return MAPPER.writeValueAsBytes(value.length == 0 ? List.of(kind) : Arrays.asList(kind, value[0]));
    }

    private static byte[] prefix(long sequence) throws IOException {
        var node = MAPPER.createObjectNode();
        var script = node.putObject("script").put("lang", "painless").put("source", SCRIPT);
        var params = script.putObject("params").put("seq", sequence);
        params.putArray("events");
        byte[] complete = MAPPER.writeValueAsBytes(node);
        return Arrays.copyOf(complete, complete.length - SUFFIX.length);
    }

    private static String loadScript() {
        try (var stream = JsonDocumentChunks.class.getResourceAsStream("/large-documents/assemble.painless")) {
            if (stream == null) {
                throw new IllegalStateException("Missing document assembly script");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() throws IOException {
        parser.close();
    }
}
