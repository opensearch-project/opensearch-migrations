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

/**
 * Resolve solrConfig from a bindings object.
 * @param bindingsObj - The bindings object (may be undefined in tests or when
 *   solrConfigXmlFile is not configured).
 * @returns The solrConfig object, or undefined when not present in bindings.
 */
export function resolveSolrConfig(bindingsObj: any): any {
  return bindingsObj?.solrConfig ?? undefined;
}

const solrConfig = resolveSolrConfig(
  typeof bindings !== 'undefined' ? bindings : undefined, //NOSONAR — typeof required for undeclared closure var
);

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

// Read fieldTypes from bindings once at init. Java provides a flat map of
// fieldName → solrTypeClass (e.g. {"title":"solr.TextField","id":"solr.StrField"})
// resolved from managed-schema.xml via solrSchemaXmlFile config.
// Empty map when solrSchemaXmlFile is not configured — fieldRule falls back to match.
const EMPTY_FIELD_TYPES: ReadonlyMap<string, string> = new Map();
export function resolveFieldTypes(bindingsObj: any): ReadonlyMap<string, string> {
  if (bindingsObj?.fieldTypes) {
    return new Map(Object.entries(bindingsObj.fieldTypes as Record<string, string>));
  }
  return EMPTY_FIELD_TYPES;
}

// Read fieldTypes from bindings once at init.
const fieldTypes = resolveFieldTypes(
  typeof bindings !== 'undefined' ? bindings : undefined, //NOSONAR — typeof required for undeclared closure var
);

export function transform(msg: JavaMap): JavaMap {
  const ctx = buildRequestContext(msg);
  if (ctx.endpoint === 'unknown') return msg;
  ctx.solrConfig = solrConfig;
  ctx.fieldTypes = fieldTypes;
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
