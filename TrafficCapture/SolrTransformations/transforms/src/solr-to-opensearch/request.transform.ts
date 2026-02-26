/**
 * Solr → OpenSearch request transform.
 *
 * Thin entry point — parses context once, runs the pipeline, writes output
 * back to the Java Map using .set() for zero-serialization GraalVM interop.
 *
 * The GraalVM closure wraps this as: (function(bindings) { return transform; })
 */
import { buildRequestContext } from './context';
import type { JavaMap } from './context';
import { runPipeline } from './pipeline';
import { requestRegistry } from './registry';

export function transform(msg: JavaMap): JavaMap {
  const ctx = buildRequestContext(msg);
  if (ctx.endpoint === 'unknown') return msg;
  runPipeline(requestRegistry, ctx);
  if (Object.keys(ctx.body).length > 0) {
    var payload = msg.get('payload');
    if (!payload) {
      payload = new Map();
      msg.set('payload', payload);
    }
    payload.set('inlinedTextBody', JSON.stringify(ctx.body));
  }
  return msg;
}
