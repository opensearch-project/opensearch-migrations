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

// Read solrConfig from bindings once at init (closure, not global mutable state).
// bindings is injected by Java via JavascriptTransformer's bindingsObject.
declare const bindings: any;
const solrConfig = (typeof bindings !== 'undefined' && bindings?.solrConfig) //NOSONAR — typeof required for undeclared closure var
  ? bindings.solrConfig
  : undefined;

export function transform(msg: JavaMap): JavaMap {
  const ctx = buildRequestContext(msg);
  if (ctx.endpoint === 'unknown') return msg;
  ctx.solrConfig = solrConfig;
  runPipeline(requestRegistry, ctx);
  if (ctx.body.size > 0) {
    let payload = msg.get('payload');
    if (!payload) {
      payload = new Map();
      msg.set('payload', payload);
    }
    payload.set('inlinedJsonBody', ctx.body);
  }
  return msg;
}
