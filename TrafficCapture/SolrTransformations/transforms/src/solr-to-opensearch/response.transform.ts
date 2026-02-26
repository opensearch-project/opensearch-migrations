/**
 * OpenSearch → Solr response transform.
 *
 * Thin entry point — parses context once, runs the pipeline, serializes output.
 *
 * The Java shim bundles {request, response} into a single map before calling
 * transformJson(), following the same pattern as the replayer's tuple transforms.
 * This transform unpacks both, runs the response pipeline with full request
 * context, then serializes the transformed response body back.
 */
import type { HttpRequestMessage, HttpResponseMessage } from '../types';
import { buildResponseContext } from './context';
import { runPipeline } from './pipeline';
import { responseRegistry } from './registry';

function applyTransform(input: { request: HttpRequestMessage; response: HttpResponseMessage }): void {
  const ctx = buildResponseContext(input.request, input.response);
  if (ctx.endpoint === 'unknown') return;
  runPipeline(responseRegistry, ctx);
  input.response.payload = { inlinedTextBody: JSON.stringify(ctx.responseBody) };
}

export function transform(msg: unknown): unknown {
  const input = msg as { request: HttpRequestMessage; response: HttpResponseMessage };
  if (!input.request || !input.response) return msg;
  applyTransform(input);
  return input;
}
