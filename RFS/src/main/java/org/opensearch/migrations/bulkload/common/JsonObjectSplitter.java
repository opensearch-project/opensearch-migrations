package org.opensearch.migrations.bulkload.common;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import lombok.AllArgsConstructor;
import lombok.experimental.UtilityClass;

/**
 * Divides one JSON object into smaller JSON objects that deep-merge, in order, back into the original.
 *
 * <p>Cuts fall only between complete members, and every member is copied from the original bytes, so
 * strings, numbers and arrays are never re-encoded or divided. An object-valued member too large for one
 * fragment is divided between its own members; each of its fragments re-opens the enclosing objects.
 */
@UtilityClass
class JsonObjectSplitter {
    // The shared mapper's factory, for its raised limits on string and name length.
    private static final JsonFactory FACTORY = ObjectMapperFactory.createDefaultMapper().getFactory()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    /** A member's byte range, from the quote opening its name to the end of its value. */
    @AllArgsConstructor
    private static final class Member {
        final int start;
        final int valueStart;
        final int end;
        final List<Member> children;
    }

    /**
     * @return fragments of at most {@code maxFragmentBytes} each, in the order they must be merged
     * @throws IOException if the object cannot be divided without changing what the target would store:
     *                     an indivisible value is too large, a field name repeats, or a fractional number
     *                     would be rounded when the target rewrites the merged source
     */
    static List<byte[]> split(byte[] json, int maxFragmentBytes) throws IOException {
        List<Member> members;
        try (var parser = FACTORY.createParser(json)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("the document is not a JSON object");
            }
            members = readObject(parser, json);
        }
        var fragments = new ArrayList<byte[]>();
        pack(json, members, new byte[] {'{'}, new byte[] {'}'}, maxFragmentBytes, fragments);
        return fragments;
    }

    /** Reads the members of the object the parser has just entered, leaving the parser on its END_OBJECT. */
    private static List<Member> readObject(JsonParser parser, byte[] json) throws IOException {
        var members = new ArrayList<Member>();
        var token = parser.nextToken();
        while (token == JsonToken.FIELD_NAME) {
            int start = offset(parser);
            token = parser.nextToken();
            int valueStart = offset(parser);
            List<Member> children = null;
            if (token == JsonToken.START_OBJECT) {
                children = readObject(parser, json);
            } else {
                checkValue(parser);
            }
            token = parser.nextToken();
            // The next token is this object's next field name or its closing brace.
            int end = offset(parser);
            if (json[start] != '"' || (token == JsonToken.END_OBJECT && json[end] != '}')) {
                throw new IOException("unexpected token offsets");
            }
            // Only a comma and JSON whitespace can separate the value from that token.
            while (", \t\r\n".indexOf(json[end - 1]) >= 0) {
                end--;
            }
            members.add(new Member(start, valueStart, end, children));
        }
        return members;
    }

    /** Consumes a scalar or array, including anything nested in it. */
    private static void checkValue(JsonParser parser) throws IOException {
        int depth = 0;
        do {
            var token = parser.currentToken();
            if (token.isStructStart()) {
                depth++;
            } else if (token.isStructEnd()) {
                depth--;
            } else if (token == JsonToken.VALUE_NUMBER_FLOAT) {
                requireExactDouble(parser.getText());
            }
        } while (depth > 0 && parser.nextToken() != null);
    }

    /**
     * A partial update makes the target parse the stored source and write it back, reading fractional
     * numbers as 64-bit floats. A literal that does not survive that round trip would be silently altered.
     */
    private static void requireExactDouble(String literal) throws IOException {
        double value = Double.parseDouble(literal);
        if (!Double.isFinite(value)
            || new BigDecimal(literal).compareTo(new BigDecimal(Double.toString(value))) != 0) {
            throw new IOException("the number " + literal + " is not exact as a 64-bit float");
        }
    }

    private static void pack(
        byte[] json, List<Member> members, byte[] open, byte[] close, int maxFragmentBytes, List<byte[]> fragments
    ) throws IOException {
        int wrapping = open.length + close.length;
        var pending = new ByteArrayOutputStream();
        for (var member : members) {
            int length = member.end - member.start;
            if (wrapping + length > maxFragmentBytes) {
                if (member.children == null || member.children.isEmpty()) {
                    throw new IOException("a single value needs " + (wrapping + length) + " bytes, above the limit of "
                        + maxFragmentBytes);
                }
                flush(pending, open, close, fragments);
                // Re-open this object in each of its fragments: `"name":{` ... `}`.
                pack(json, member.children,
                    concat(open, json, member.start, member.valueStart + 1 - member.start),
                    concat(new byte[] {'}'}, close, 0, close.length), maxFragmentBytes, fragments);
                continue;
            }
            if (pending.size() > 0 && wrapping + pending.size() + 1 + length > maxFragmentBytes) {
                flush(pending, open, close, fragments);
            }
            if (pending.size() > 0) {
                pending.write(',');
            }
            pending.write(json, member.start, length);
        }
        flush(pending, open, close, fragments);
    }

    private static void flush(ByteArrayOutputStream pending, byte[] open, byte[] close, List<byte[]> fragments) {
        if (pending.size() > 0) {
            fragments.add(concat(concat(open, pending.toByteArray(), 0, pending.size()), close, 0, close.length));
            pending.reset();
        }
    }

    private static byte[] concat(byte[] first, byte[] second, int offset, int length) {
        var joined = new byte[first.length + length];
        System.arraycopy(first, 0, joined, 0, first.length);
        System.arraycopy(second, offset, joined, first.length, length);
        return joined;
    }

    private static int offset(JsonParser parser) {
        return (int) parser.currentTokenLocation().getByteOffset();
    }
}
