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
     * This is the key under the 'payload' object whose value is the parsed json from the HTTP message payload.
     * Notice that there aren't yet other ways to access the payload contents.  If the content-type was not json,
     * the payload object will be an empty map.
     */
    public static final String INLINED_JSON_BODY_DOCUMENT_KEY = "inlinedJsonBody";
    /**
     * for the type application
     */
    public static final String INLINED_NDJSON_BODIES_DOCUMENT_KEY = "inlinedJsonSequenceBodies";
    /**
     * This maps to a ByteBuf that is owned by the caller.
     * Any consumers should retain if they need to access it later.  This may be UTF8, UTF16 encoded, or something else.
     */
    public static final String INLINED_BINARY_BODY_DOCUMENT_KEY = "inlinedBinaryBody";
    public static final String INLINED_BASE64_BODY_DOCUMENT_KEY = "inlinedBase64Body";

    /**
     * This maps the body for utf-8 encoded text. This is used for text/plain encoding.
     */
    public static final String INLINED_TEXT_BODY_DOCUMENT_KEY = "inlinedTextBody";

}
