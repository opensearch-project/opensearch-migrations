/**
 * Solr → OpenSearch request transform.
 *
 * Thin entry point — parses context once, runs the pipeline, serializes output.
 * The GraalVM closure wraps this as: (function(bindings) { return transform; })
 */
import type { HttpRequestMessage } from '../types';
import { buildRequestContext } from './context';
import { runPipeline } from './pipeline';
import { requestRegistry } from './registry';

export function transform(msg: HttpRequestMessage): HttpRequestMessage {
  const ctx = buildRequestContext(msg);
  if (ctx.endpoint === 'unknown') return msg;
  runPipeline(requestRegistry, ctx);
  if (Object.keys(ctx.body).length > 0) {
    msg.payload = { inlinedTextBody: JSON.stringify(ctx.body) };
  }
  return msg;
}
