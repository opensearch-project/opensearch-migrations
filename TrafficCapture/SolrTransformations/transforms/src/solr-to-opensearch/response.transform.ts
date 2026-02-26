/**
 * OpenSearch → Solr response transform.
 *
 * Thin entry point — parses context once, runs the pipeline, writes the body
 * Map back as inlinedJsonBody. Zero serialization in JavaScript — Jackson
 * handles JSON on the Java side.
 *
 * The Java shim bundles {request, response} into a single LinkedHashMap before
 * calling transformJson(), following the same pattern as the replayer's tuple
 * transforms.
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
  payload.set('inlinedJsonBody', ctx.responseBody);
  return msg;
}
