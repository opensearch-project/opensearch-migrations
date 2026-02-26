/**
 * Type definitions for the IJsonTransformer contract.
 *
 * These types mirror the Java-side schemas used by the replayer's
 * transformation framework. There are two transform contexts:
 *
 * 1. **Request transforms** — receive a single {@link HttpRequestMessage}
 *    matching {@link JsonKeysForHttpMessage} (v2 schema). Configured via
 *    `--transformer-config`.
 *
 * 2. **Tuple transforms** — receive a {@link SourceTargetTuple} bundling
 *    request + response(s) into one JSON object, matching
 *    {@link ResultsToLogsConsumer.toJSONObject}. Configured via
 *    `--tuple-transformer-config`.
 *
 * Both go through the same interface:
 *    `IJsonTransformer.transformJson(Object) → Object`
 *
 * @see IJsonTransformer — the single-method interface
 * @see JsonKeysForHttpMessage — canonical key definitions for v2 schema
 * @see ResultsToLogsConsumer.toJSONObject — tuple construction
 * @see ParsedHttpMessagesAsDicts — individual message parsing
 */

// ---------------------------------------------------------------------------
// Payload
// ---------------------------------------------------------------------------

/**
 * HTTP message payload — at most one body key will be present.
 *
 * @see JsonKeysForHttpMessage.INLINED_JSON_BODY_DOCUMENT_KEY
 * @see JsonKeysForHttpMessage.INLINED_TEXT_BODY_DOCUMENT_KEY
 * @see JsonKeysForHttpMessage.INLINED_NDJSON_BODIES_DOCUMENT_KEY
 * @see JsonKeysForHttpMessage.INLINED_BINARY_BODY_DOCUMENT_KEY
 * @see JsonKeysForHttpMessage.INLINED_BASE64_BODY_DOCUMENT_KEY
 */
export interface Payload {
  inlinedJsonBody?: unknown;
  inlinedTextBody?: string;
  inlinedJsonSequenceBodies?: unknown[];
  inlinedBinaryBody?: unknown;
  inlinedBase64Body?: string;
}

// ---------------------------------------------------------------------------
// Headers
// ---------------------------------------------------------------------------

/**
 * HTTP header map. Values are string (single) or string[] (multi-valued).
 *
 * In the v2 schema (request transforms), headers are nested under a
 * `"headers"` key as a case-insensitive map.
 *
 * In the tuple schema (ParsedHttpMessagesAsDicts), headers are flattened
 * into the top-level map as `"header-1": "Content-Type: application/json"`.
 *
 * @see JsonKeysForHttpMessage.HEADERS_KEY
 * @see ListKeyAdaptingCaseInsensitiveHeadersMap
 */
export interface HttpHeaders {
  [key: string]: string | string[];
}

// ---------------------------------------------------------------------------
// Request message — v2 schema (JsonKeysForHttpMessage)
// ---------------------------------------------------------------------------

/**
 * An HTTP request as seen by request transforms.
 *
 * Matches the v2 schema from {@link HttpJsonRequestWithFaultingPayload}.
 * The GraalVM bridge passes this to `transformJson()` for request transforms.
 *
 * @see JsonKeysForHttpMessage.METHOD_KEY — "method"
 * @see JsonKeysForHttpMessage.URI_KEY — "URI"
 * @see JsonKeysForHttpMessage.PROTOCOL_KEY — "protocol"
 * @see JsonKeysForHttpMessage.HEADERS_KEY — "headers"
 * @see JsonKeysForHttpMessage.PAYLOAD_KEY — "payload"
 * @see JsonKeysForHttpMessage.HTTP_MESSAGE_SCHEMA_VERSION_KEY — "transformerMessageVersion"
 * @see JsonKeysForHttpMessage.PRESERVE_KEY — "preserve"
 */
export interface HttpRequestMessage {
  method: string;
  URI: string;
  protocol?: string;
  headers?: HttpHeaders;
  payload?: Payload;
  transformerMessageVersion?: number;
  preserve?: string[];
}

/**
 * An HTTP response as seen by request transforms.
 *
 * Matches the v2 schema from {@link HttpJsonResponseWithFaultingPayload}.
 *
 * Note: `code` is a string in Java (`HttpJsonResponseWithFaultingPayload.code()`
 * returns String). The tuple schema converts it to int via `Integer.parseInt()`.
 *
 * @see JsonKeysForHttpMessage.STATUS_CODE_KEY — "code"
 * @see JsonKeysForHttpMessage.STATUS_REASON_KEY — "reason"
 */
export interface HttpResponseMessage {
  code: string;
  reason?: string;
  protocol?: string;
  headers?: HttpHeaders;
  payload?: Payload;
  transformerMessageVersion?: number;
  preserve?: string[];
}

// ---------------------------------------------------------------------------
// Tuple message — ParsedHttpMessagesAsDicts schema
// ---------------------------------------------------------------------------

/**
 * An HTTP request in the tuple schema.
 *
 * Built by {@link ParsedHttpMessagesAsDicts.convertRequest}. Headers are
 * flattened at the top level as numbered keys (`"header-1"`, `"header-2"`).
 *
 * @see ParsedHttpMessagesAsDicts.REQUEST_URI_KEY — "Request-URI"
 * @see ParsedHttpMessagesAsDicts.METHOD_KEY — "Method"
 * @see ParsedHttpMessagesAsDicts.HTTP_VERSION_KEY — "HTTP-Version"
 */
export interface TupleRequest {
  'Request-URI': string;
  'Method': string;
  'HTTP-Version': string;
  payload?: Payload;
  /** Headers flattened as "header-1", "header-2", etc. */
  [header: string]: unknown;
}

/**
 * An HTTP response in the tuple schema.
 *
 * Built by {@link ParsedHttpMessagesAsDicts.convertResponse}. Headers are
 * flattened at the top level. Status-Code is an integer (parsed from the
 * String stored in HttpJsonResponseWithFaultingPayload).
 *
 * @see ParsedHttpMessagesAsDicts.STATUS_CODE_KEY — "Status-Code"
 * @see ParsedHttpMessagesAsDicts.RESPONSE_TIME_MS_KEY — "response_time_ms"
 * @see ParsedHttpMessagesAsDicts.HTTP_VERSION_KEY — "HTTP-Version"
 */
export interface TupleResponse {
  'HTTP-Version': string;
  'Status-Code': number;
  'Reason-Phrase': string;
  response_time_ms: number;
  payload?: Payload;
  /** Headers flattened as "header-1", "header-2", etc. */
  [header: string]: unknown;
}

/**
 * A source-target capture tuple as built by
 * {@link ResultsToLogsConsumer.toJSONObject}.
 *
 * This is the JSON shape passed to `IJsonTransformer.transformJson()` for
 * tuple transforms (configured via `--tuple-transformer-config`).
 *
 * @see ResultsToLogsConsumer.toJSONObject — builds this map
 * @see TupleTransformationParams — CLI args for tuple transforms
 */
export interface SourceTargetTuple {
  sourceRequest?: TupleRequest;
  sourceResponse?: TupleResponse;
  targetRequest?: TupleRequest;
  targetResponses: TupleResponse[];
  connectionId: string;
  numRequests: number;
  numErrors: number;
  error?: string;
}

// ---------------------------------------------------------------------------
// Transform function — mirrors IJsonTransformer.transformJson(Object)
// ---------------------------------------------------------------------------

/**
 * A transform function.
 *
 * Mirrors `IJsonTransformer.transformJson(Object) → Object`. The input
 * is fully dynamic — it may be an {@link HttpRequestMessage} (request
 * transform), an {@link HttpResponseMessage}, or a {@link SourceTargetTuple}
 * (tuple transform). The transformer inspects the shape to decide what to do.
 *
 * @see IJsonTransformer.transformJson
 */
export type TransformFn = (msg: unknown) => unknown;
