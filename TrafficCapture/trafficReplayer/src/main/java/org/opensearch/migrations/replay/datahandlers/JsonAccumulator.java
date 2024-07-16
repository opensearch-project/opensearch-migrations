package org.opensearch.migrations.replay.datahandlers;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.async.ByteBufferFeeder;

import lombok.extern.slf4j.Slf4j;

/**
 * Consume data, building the json object tree as it goes.  This returns null until the top-level
 * object or array has been built, in which case that value will be returned.
 */
@Slf4j
public class JsonAccumulator {

    private final JsonParser parser;
    /**
     * This stack will contain JSON Objects, FieldName tokens, and ArrayLists.
     * ArrayLists will be converted into arrays upon popping them from the stack.
     * FieldName tokens, once bound with a child (a scalar, object or array) will
     * be popped and added to the object that is situated directly above the Field
     * Name in the stack.
     */
    private final Deque<Object> jsonObjectStack;

    public JsonAccumulator() throws IOException {
        jsonObjectStack = new ArrayDeque<>();
        JsonFactory factory = new JsonFactory();
        parser = factory.createNonBlockingByteBufferParser();
    }

    protected Map<String, Object> createMap() {
        return new LinkedHashMap<>();
    }

    /**
     * Returns the top-level object once it has been fully constructed or null if more input is still required.
     * @param byteBuffer
     * @return
     * @throws IOException
     */
    public Object consumeByteBuffer(ByteBuffer byteBuffer) throws IOException {
        ByteBufferFeeder feeder = (ByteBufferFeeder) parser.getNonBlockingInputFeeder();
        log.trace("Consuming bytes: " + byteBuffer.toString());
        feeder.feedInput(byteBuffer);

        while (!parser.isClosed()) {
            var token = parser.nextToken();
            if (token == null) {
                // pipeline stall - need more data
                break;
            }

            log.trace(this + " ... adding token=" + token);
            switch (token) {
                case FIELD_NAME:
                    jsonObjectStack.push(parser.getText());
                    break;
                case START_ARRAY:
                    jsonObjectStack.push(new ArrayList<>());
                    break;
                case END_ARRAY: {
                    var array = ((ArrayList) jsonObjectStack.pop()).toArray();
                    pushCompletedValue(array);
                    if (jsonObjectStack.isEmpty()) {
                        return array;
                    }
                    break;
                }
                case START_OBJECT:
                    jsonObjectStack.push(createMap());
                    break;
                case END_OBJECT: {
                    var popped = jsonObjectStack.pop();
                    if (jsonObjectStack.isEmpty()) {
                        return popped;
                    } else {
                        pushCompletedValue(popped);
                    }
                    break;
                }
                case VALUE_NULL:
                    pushCompletedValue(null);
                    break;
                case VALUE_TRUE:
                    pushCompletedValue(true);
                    break;
                case VALUE_FALSE:
                    pushCompletedValue(false);
                    break;
                case VALUE_STRING:
                    pushCompletedValue(parser.getText());
                    break;
                case VALUE_NUMBER_INT:
                    pushCompletedValue(parser.getIntValue());
                    break;
                case VALUE_NUMBER_FLOAT:
                    pushCompletedValue(parser.getFloatValue());
                    break;
                case NOT_AVAILABLE:
                    // pipeline stall - need more data
                    return null;
                case VALUE_EMBEDDED_OBJECT:
                default:
                    throw new IllegalStateException("Unexpected value type: " + token);
            }
        }
        return null;
    }

    private void pushCompletedValue(Object value) {
        var topElement = jsonObjectStack.peek();
        if (topElement instanceof String) {
            var fieldName = (String) jsonObjectStack.pop();
            var grandParent = jsonObjectStack.peek();
            if (grandParent instanceof Map) {
                ((Map) grandParent).put(fieldName, value);
            } else {
                throw new IllegalStateException("Stack mismatch, cannot push a value " + toString());
            }
        } else if (topElement instanceof ArrayList) {
            ((ArrayList) topElement).add(value);
        }
    }

    @Override
    public String toString() {
        var jsonStackString = "" + jsonObjectStack.size();
        final StringBuilder sb = new StringBuilder("JsonAccumulator{");
        sb.append(", jsonObjectStack=").append(jsonStackString);
        sb.append('}');
        return sb.toString();
    }
}
