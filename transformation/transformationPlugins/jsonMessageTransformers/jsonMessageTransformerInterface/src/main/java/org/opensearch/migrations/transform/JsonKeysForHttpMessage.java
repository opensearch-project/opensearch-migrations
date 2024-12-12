package org.opensearch.migrations.transform;

public class JsonKeysForHttpMessage {

    private JsonKeysForHttpMessage() {}

    public static final String HTTP_MESSAGE_SCHEMA_VERSION_KEY = "transformerMessageVersion";
    public static final String METHOD_KEY = "method";
    public static final String STATUS_CODE_KEY = "code";
    public static final String STATUS_REASON_KEY = "reason";
    public static final String URI_KEY = "URI";
    public static final String PROTOCOL_KEY = "protocol";
    public static final String HEADERS_KEY = "headers";
    public static final String PAYLOAD_KEY = "payload";

    /**
     * <p>This key is valid at the top level and as a direct within a "payload" value.
     * After a transformation has completed, these objects will be excised and replaced with
     * the original nodes from the original document.</p>
     *
     * <p>For example. the following will cause the original payload to be preserved.</p>
     * <code>`"preserve": [ "payload" ]`</code>
     * <p>The following will cause the original headers and payload to be preserved.</p>
     * <code>`"preserve": [ "headers", "payload" ]`</code>
     *
     * <p>Notice that any already existing items will be replaced.</p>
     */
    public static final String PRESERVE_KEY = "preserve";

    /**
     * This is the key under the 'payload' object whose value is the parsed json from the HTTP message payload.
     * Notice that there aren't yet other ways to access the payload contents.  If the content-type was not json,
     * the payload object will be an empty map.
     */
    public static final String INLINED_JSON_BODY_DOCUMENT_KEY = "inlinedJsonBody";
    /**
     * Like INLINED_JSON_BODY_DOCUMENT_KEY, this key is used directly under the 'payload' object.  Its value
     * will be a list of json documents that represent the lines of the original ndjson payload, when present.
     */
    public static final String INLINED_NDJSON_BODIES_DOCUMENT_KEY = "inlinedJsonSequenceBodies";
    /**
     * Like INLINED_JSON_BODY_DOCUMENT_KEY, this key is used directly under the 'payload' object.  Its value
     * maps to a ByteBuf that is owned by the caller.
     * Any consumers should retain if they need to access it later.  This may be UTF8, UTF16 encoded, or something else.
     */
    public static final String INLINED_BINARY_BODY_DOCUMENT_KEY = "inlinedBinaryBody";
    public static final String INLINED_BASE64_BODY_DOCUMENT_KEY = "inlinedBase64Body";

    /**
     * This maps the body for utf-8 encoded text. This is used for text/plain encoding.
     */
    public static final String INLINED_TEXT_BODY_DOCUMENT_KEY = "inlinedTextBody";

}
