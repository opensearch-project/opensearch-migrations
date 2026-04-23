/**
 * Single document update — /solr/{collection}/update/json/docs → /{collection}/_doc/{id}
 *
 * Translates Solr's JSON document ingestion shortcut to OpenSearch's Index API.
 *
 * Solr:       POST /solr/{collection}/update/json/docs  {"id":"1","title":"hello"}
 * OpenSearch:  PUT /{collection}/_doc/1                  {"id":"1","title":"hello"}
 *
 * Commit handling:
 *   - commit=true → ?refresh=true (both make doc immediately searchable)
 *   - commitWithin=N → ?refresh=true (OpenSearch has no timed refresh equivalent,
 *     so we refresh immediately. This is more aggressive than Solr's batched commit
 *     but satisfies the "searchable within N ms" contract. See LIMITATIONS.md.)
 *
 * Fail-fast on:
 *   - Missing or empty body
 *   - Array body (bulk not supported yet)
 *   - Missing "id" field in document
 *
 * The body is passed through unchanged — no field-level translation needed.
 */
import type { MicroTransform } from '../pipeline';
import type { RequestContext } from '../context';

/** Detect GraalVM Java ArrayList — exposes numeric keys + length, unlike a Map. */
function isJavaList(obj: any): boolean {
  return typeof obj.get === 'function' && obj.get('0') !== undefined && obj.get('length') !== undefined;
}

export const request: MicroTransform<RequestContext> = {
  name: 'update-doc',
  apply: (ctx) => {
    const body = ctx.body;

    if (!body || body.size === 0) {
      throw new Error('[update-doc] Request body is empty — expected a JSON document');
    }

    if (isJavaList(body)) {
      throw new Error('[update-doc] Array/bulk updates not supported yet — send one document at a time');
    }

    const id = body.get('id');
    if (id == null || id === '') {
      throw new Error('[update-doc] Document must have an "id" field');
    }

    // Build target URI with structured params
    const targetParams = new URLSearchParams();
    if (ctx.params.get('commit') === 'true' || ctx.params.has('commitWithin')) {
      targetParams.set('refresh', 'true');
    }

    const query = targetParams.toString();
    const suffix = query ? '?' + query : '';
    const uri = `/${ctx.collection}/_doc/${encodeURIComponent(String(id))}${suffix}`;

    ctx.msg.set('URI', uri);
    ctx.msg.set('method', 'PUT');
  },
};
