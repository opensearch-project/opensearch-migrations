/**
 * OpenSearch → Solr response transform.
 *
 * Thin entry point — parses context once, runs the pipeline, writes output
 * back to the Java Map using .set() for zero-serialization GraalVM interop.
 *
 * The Java shim bundles {request, response} into a single LinkedHashMap before
 * calling transformJson(), following the same pattern as the replayer's tuple
 * transforms. This transform reads both via .get(), runs the response pipeline
 * with full request context, then writes the transformed body back via .set().
 */
import { buildResponseContext } from './context';
import type { JavaMap } from './context';
import { runPipeline } from './pipeline';
import { responseRegistry } from './registry';

export function transform(msg: JavaMap): JavaMap {
  const request = msg.get('request');
  const response = msg.get('response');
  if (!request || !response) return msg;

  const ctx = buildResponseContext(request, response);
  if (ctx.endpoint === 'unknown') return msg;
  runPipeline(responseRegistry, ctx);

  var payload = response.get('payload');
  if (!payload) {
    payload = new Map();
    response.set('payload', payload);
  }
  payload.set('inlinedTextBody', JSON.stringify(ctx.responseBody));
  return msg;
}
