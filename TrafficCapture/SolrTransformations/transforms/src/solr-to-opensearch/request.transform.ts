/**
 * Solr → OpenSearch request transform.
 *
 * Thin entry point — parses context once, runs the pipeline, writes the body
 * Map back as inlinedJsonBody. Zero serialization in JavaScript — Jackson
 * handles JSON on the Java side.
 */
import { buildRequestContext } from './context';
import type { JavaMap } from './context';
import { runPipeline } from './pipeline';
import { requestRegistry } from './registry';

export function transform(msg: JavaMap): JavaMap {
  const ctx = buildRequestContext(msg);
  if (ctx.endpoint === 'unknown') return msg;
  runPipeline(requestRegistry, ctx);
  if (ctx.body.size > 0) {
    var payload = msg.get('payload');
    if (!payload) {
      payload = new Map();
      msg.set('payload', payload);
    }
    payload.set('inlinedJsonBody', ctx.body);
  }
  return msg;
}
