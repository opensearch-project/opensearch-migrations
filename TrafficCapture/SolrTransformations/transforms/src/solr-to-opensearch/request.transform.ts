/**
 * Solr → OpenSearch request transform — the GraalVM entry point.
 *
 * This is the function that the Java-side JavascriptTransformer calls for
 * every incoming HTTP request. It's intentionally thin:
 *   1. Parse the raw Java Map into a typed RequestContext (once)
 *   2. Guard against non-JSON wt parameter (skip transformation)
 *   3. Run the micro-transform pipeline (select-uri, query-q, filter-fq, etc.)
 *   4. Write the transformed body back as inlinedJsonBody for Jackson serialization
 *
 * The `msg` parameter is a Java LinkedHashMap passed through GraalVM's
 * `allowMapAccess(true)`. We use .get()/.set() — never bracket notation —
 * because GraalVM bridges these to Java Map methods with zero serialization.
 */
import { buildRequestContext } from './context';
import type { JavaMap } from './context';
import { runPipeline } from './pipeline';
import { requestRegistry } from './registry';

export function transform(msg: JavaMap): JavaMap {
  const ctx = buildRequestContext(msg);
  if (ctx.endpoint === 'unknown') return msg;

  // wt guard: skip transformation for non-JSON response formats (Req 11.3)
  const wt = ctx.params.get('wt');
  if (wt && wt !== 'json') {
    console.error(`[request.transform] Skipping transformation: wt='${wt}' is not JSON`);
    return msg;
  }

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
