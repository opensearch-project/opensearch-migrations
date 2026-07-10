/**
 * Delete by query — {"delete":{"query":"<solr-query>"}} → POST /_delete_by_query
 *
 * Translates a Solr delete-by-query command into OpenSearch's _delete_by_query API.
 * The Solr query string goes through the existing translateQ pipeline (parser → AST
 * → transformer), producing the same DSL the read path uses.
 *
 * Commit handling:
 *   - commit=true → ?refresh=true&wait_for_completion=true
 *   - No commit   → ?wait_for_completion=true only
 *
 * Fail-fast policy: delete is destructive — no passthrough on unparseable queries.
 */
import type { MicroTransform } from '../pipeline';
import type { RequestContext, ResponseContext } from '../context';
import { translateQ } from '../query-engine/orchestrator/translateQ';

export const request: MicroTransform<RequestContext> = {
  name: 'delete-by-query',
  match: (ctx) => ctx.body != null && typeof ctx.body.has === 'function' && ctx.body.has('query'),
  apply: (ctx) => {
    const queryStr = ctx.body.get('query');
    if (queryStr == null || String(queryStr).trim() === '') {
      throw new Error('[delete-by-query] query string is empty — nothing to delete');
    }

    const solrQuery = String(queryStr).trim();
    const params = new Map<string, string>();
    for (const [key, value] of ctx.params.entries()) {
      params.set(key, value);
    }
    params.set('q', solrQuery);
    const { dsl } = translateQ(params, 'fail-fast');

    const body = new Map<string, unknown>([['query', dsl]]);

    const targetParams = new URLSearchParams();
    targetParams.set('wait_for_completion', 'true');
    if (ctx.params.get('commit') === 'true' || ctx.params.has('commitWithin')) {
      targetParams.set('refresh', 'true');
    }

    ctx.msg.set('URI', `/${ctx.collection}/_delete_by_query?${targetParams.toString()}`);
    ctx.msg.set('method', 'POST');
    ctx.body = body;
  },
};

/**
 * Response: _delete_by_query returns {took, total, deleted, batches, failures, ...}
 * → Solr {responseHeader:{status, QTime:took}}
 *
 * Status mapping:
 *   - deleted === total && no failures → status 0
 *   - version_conflicts > 0 or failures non-empty → status 1
 */
export function isDeleteByQueryResponse(body: any): boolean {
  return body != null && typeof body.has === 'function'
    && body.has('deleted') && body.has('total');
}

export const response: MicroTransform<ResponseContext> = {
  name: 'delete-by-query-response',
  match: (ctx) => isDeleteByQueryResponse(ctx.responseBody),
  apply: (ctx) => {
    const body = ctx.responseBody;
    const took = typeof body.get('took') === 'number' ? body.get('took') : 0;
    const total = typeof body.get('total') === 'number' ? body.get('total') : 0;
    const deleted = typeof body.get('deleted') === 'number' ? body.get('deleted') : 0;
    const versionConflicts = typeof body.get('version_conflicts') === 'number' ? body.get('version_conflicts') : 0;
    const failures = body.get('failures');
    const hasFailures = (failures && typeof failures.length === 'number' && failures.length > 0);
    const hasConflicts = versionConflicts > 0;
    const incomplete = deleted < total;
    const failed = hasFailures || hasConflicts || incomplete;

    const keys = Array.from(body.keys());
    for (const key of keys) {
      body.delete(key);
    }

    body.set(
      'responseHeader',
      new Map<string, unknown>([
        ['status', failed ? 1 : 0],
        ['QTime', took],
      ]),
    );
  },
};
