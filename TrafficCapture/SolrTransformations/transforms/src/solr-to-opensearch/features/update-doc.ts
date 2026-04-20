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
import type { RequestContext, ResponseContext } from '../context';

/** Detect GraalVM Java ArrayList — exposes numeric keys + length, unlike a Map. */
function isJavaList(obj: any): boolean {
  return typeof obj.get === 'function' && obj.get('0') !== undefined && obj.get('length') !== undefined;
}

export const request: MicroTransform<RequestContext> = {
  name: 'update-doc',
  match: (ctx) => /\/update\/json\/docs/.test(ctx.msg.get('URI') || ''),
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

/**
 * Response transform — convert OpenSearch _doc response to Solr update response.
 *
 * OpenSearch returns: {"_index":"mycore","_id":"1","result":"created","_version":1,...}
 * Solr returns:       {"responseHeader":{"status":0,"QTime":N}}
 */
export const response: MicroTransform<ResponseContext> = {
  name: 'update-doc-response',
  match: (ctx) => ctx.responseBody.has('result') && ctx.responseBody.has('_id'),
  apply: (ctx) => {
    const result = ctx.responseBody.get('result');
    const status = (result === 'created' || result === 'updated') ? 0 : 1;

    // Clear OpenSearch-specific fields
    const keys = Array.from(ctx.responseBody.keys());
    for (const key of keys) {
      ctx.responseBody.delete(key);
    }

    // Write Solr-format response.
    // QTime is 0 because OpenSearch's _doc response doesn't include processing time.
    ctx.responseBody.set(
      'responseHeader',
      new Map<string, unknown>([
        ['status', status],
        ['QTime', 0],
      ]),
    );
  },
};
