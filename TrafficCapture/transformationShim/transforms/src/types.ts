/**
 * Type definitions for HTTP messages in the IJsonTransformer contract.
 *
 * These types mirror the Java-side {@link JsonKeysForHttpMessage} keys used by
 * {@link IJsonTransformer} and the GraalVM polyglot bridge. The shim proxy
 * constructs these maps in {@link TransformingProxyHandler} before passing
 * them to your transform function.
 *
 * @see JsonKeysForHttpMessage — canonical key definitions (Java)
 * @see TransformingProxyHandler.nettyRequestToMap — request construction
 * @see TransformingProxyHandler.httpResponseToMap — response construction
 */

/**
 * HTTP header map.
 *
 * Single-valued headers are a string; multi-valued headers are a string array.
 * The GraalVM bridge preserves this from the Java `Map<String, Object>`.
 */
export interface HttpHeaders {
  [key: string]: string | string[];
}

/**
 * HTTP message payload — at most one body key will be present.
 *
 * The shim proxy currently only populates {@link inlinedTextBody}.
 * The replayer pipeline may also use {@link inlinedJsonBody} (pre-parsed JSON),
 * {@link inlinedJsonSequenceBodies} (NDJSON), or the binary variants.
 */
export interface Payload {
  /** UTF-8 text body. The shim proxy always uses this key. */
  inlinedTextBody?: string;
  /** Pre-parsed JSON body (single document). Set by the replayer when content-type is JSON. */
  inlinedJsonBody?: unknown;
  /** Pre-parsed NDJSON bodies (e.g. _bulk requests). Each element is one JSON document. */
  inlinedJsonSequenceBodies?: unknown[];
  /** Raw binary body as a byte buffer (from GraalVM HostAccess.allowBufferAccess). */
  inlinedBinaryBody?: unknown;
  /** Base64-encoded binary body. */
  inlinedBase64Body?: string;
}

/**
 * An HTTP request message as seen by transform functions.
 *
 * Built by {@link TransformingProxyHandler.nettyRequestToMap} from the
 * incoming Netty request. Your transform receives this, mutates or
 * replaces fields, and returns it.
 */
export interface HttpRequestMessage {
  /** HTTP method: "GET", "POST", "PUT", "DELETE", etc. */
  method: string;
  /** Request URI including query string, e.g. "/solr/collection/select?q=*:*". */
  URI: string;
  /** HTTP protocol version, e.g. "HTTP/1.1". */
  protocol?: string;
  /** Request headers. */
  headers?: HttpHeaders;
  /** Request body. Only present when the request has a non-empty body. */
  payload?: Payload;
  /**
   * Schema version of this message format.
   * @see JsonKeysForHttpMessage.HTTP_MESSAGE_SCHEMA_VERSION_KEY
   */
  transformerMessageVersion?: string;
  /**
   * Keys to preserve from the original (pre-transform) message.
   *
   * Example: `["payload"]` preserves the original payload after transform.
   * Example: `["headers", "payload"]` preserves both.
   *
   * @see JsonKeysForHttpMessage.PRESERVE_KEY
   */
  preserve?: string[];
}

/**
 * An HTTP response message as seen by transform functions.
 *
 * Built by {@link TransformingProxyHandler.httpResponseToMap} from the
 * backend HTTP response. Your transform receives this, mutates or
 * replaces fields, and returns it.
 */
export interface HttpResponseMessage {
  /** HTTP status code, e.g. 200, 404, 500. */
  code: number;
  /** HTTP reason phrase, e.g. "OK", "Not Found". Not always present. */
  reason?: string;
  /** HTTP protocol version, e.g. "HTTP/1.1". */
  protocol?: string;
  /** Response headers. */
  headers?: HttpHeaders;
  /** Response body. Only present when the response has a non-empty body. */
  payload?: Payload;
  /** Schema version of this message format. */
  transformerMessageVersion?: string;
  /** Keys to preserve from the original (pre-transform) message. */
  preserve?: string[];
}

/** Union type for any HTTP message (request or response). */
export type HttpMessage = HttpRequestMessage | HttpResponseMessage;

/**
 * A transform function signature.
 *
 * The GraalVM bridge calls this with a single message argument.
 * Return the transformed message (may be the same object, mutated).
 */
export type TransformFn<T extends HttpMessage> = (msg: T) => T;
