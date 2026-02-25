/**
 * Type definitions for HTTP messages passed through the TransformationShim.
 *
 * These match the Java-side JsonKeysForHttpMessage keys used by
 * IJsonTransformer and the GraalVM polyglot bridge.
 */

/** HTTP header map — values can be a single string or array of strings. */
export interface HttpHeaders {
  [key: string]: string | string[];
}

/** HTTP message payload — exactly one body key will be present. */
export interface Payload {
  /** Parsed JSON body (single document). */
  inlinedJsonBody?: unknown;
  /** Parsed NDJSON bodies (array of documents, e.g. bulk requests). */
  inlinedJsonSequenceBodies?: unknown[];
  /** UTF-8 text body (text/plain or unparsed). */
  inlinedTextBody?: string;
  /** Raw binary body as a byte buffer. */
  inlinedBinaryBody?: unknown;
  /** Base64-encoded binary body. */
  inlinedBase64Body?: string;
}

/** An HTTP request message as seen by transforms. */
export interface HttpRequestMessage {
  method: string;
  URI: string;
  protocol?: string;
  headers?: HttpHeaders;
  payload?: Payload;
}

/** An HTTP response message as seen by transforms. */
export interface HttpResponseMessage {
  code: number;
  reason?: string;
  protocol?: string;
  headers?: HttpHeaders;
  payload?: Payload;
}

/** Union type for any HTTP message (request or response). */
export type HttpMessage = HttpRequestMessage | HttpResponseMessage;

/** A transform function that takes a message and returns the transformed message. */
export type TransformFn<T extends HttpMessage> = (msg: T) => T;
