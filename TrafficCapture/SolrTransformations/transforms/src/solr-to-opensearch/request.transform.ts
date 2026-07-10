/**
 * Solr → OpenSearch request transform.
 *
 * Thin entry point — parses context once, runs the pipeline, writes the body
 * Map back as inlinedJsonBody. Zero serialization in JavaScript — Jackson
 * handles JSON on the Java side.
 *
 * Bulk requests: when a transform (e.g., the bulk builder) has already populated
 * `payload.inlinedJsonSequenceBodies`, we do not overwrite with `inlinedJsonBody`.
 * This mirrors the replayer's invariant that exactly one of the two payload-body
 * keys is set for a given request. See JsonKeysForHttpMessage.
 */
import { buildRequestContext } from './context';
import type { JavaMap } from './context';
import { runPipeline } from './pipeline';
import { requestRegistry } from './registry';
import { flushMetrics } from './metrics';

// Read solrConfig from bindings once at init (closure, not global mutable state).
// bindings is injected by Java via JavascriptTransformer's bindingsObject.
declare const bindings: any;
const solrConfig = (typeof bindings !== 'undefined' && bindings?.solrConfig) //NOSONAR — typeof required for undeclared closure var
  ? bindings.solrConfig
  : undefined;

/**
 * True when the body is a non-empty iterable/collection (Map or List). Guards against
 * writing empty bodies back to the payload while tolerating the polymorphic shape of
 * ctx.body after top-level-array ingress (List<Object>) vs. JSON-object ingress (Map).
 */
function hasContent(body: any): boolean {
  if (body == null) return false;
  if (typeof body.size === 'number') return body.size > 0;
  // Java List polyglot wrapper — exposes length but not size
  const len = body.length;
  if (typeof len === 'number') return len > 0;
  return false;
}

export function transform(msg: JavaMap): JavaMap {
  const ctx = buildRequestContext(msg);
  if (ctx.endpoint === 'unknown') return msg;
  ctx.solrConfig = solrConfig;
  runPipeline(requestRegistry, ctx);

  let payload = msg.get('payload');
  if (!payload) {
    payload = new Map();
    msg.set('payload', payload);
  }

  // If a transform has already produced an NDJSON sequence (e.g., bulk builder),
  // respect it as the authoritative body and do not overwrite with inlinedJsonBody.
  if (!payload.has('inlinedJsonSequenceBodies') && hasContent(ctx.body)) {
    payload.set('inlinedJsonBody', ctx.body);
  }

  flushMetrics(ctx._metrics, msg);
  return msg;
}
