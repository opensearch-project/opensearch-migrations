/**
 * Terms Component — translate Solr /terms endpoint or terms=true param
 * to OpenSearch terms aggregation on a match_all query.
 *
 * Request: terms.fl, terms.limit, terms.prefix, etc. → { size:0, aggs: { ... } }
 * Response: aggregations.{field}.buckets → { terms: { field: [term, count, ...] } }
 *
 * Fundamental differences:
 *   - Solr iterates the term dictionary directly (analyzed tokens, exact counts)
 *   - OpenSearch uses terms aggregation (keyword fields, approximate on multi-shard)
 *   - Solr returns flat array [term, count, term, count, ...]
 *   - OpenSearch returns bucket array [{key, doc_count}, ...]
 *
 * The request transform builds a match_all + terms agg.
 * The response transform converts buckets back to Solr's flat format.
 */
import type { MicroTransform } from '../pipeline';
import type { RequestContext, ResponseContext, JavaMap } from '../context';

// Params with no clean OpenSearch equivalent — warn and skip
const UNSUPPORTED_PARAMS = [
  'terms.lower',
  'terms.upper',
  'terms.lower.incl',
  'terms.upper.incl',
  'terms.ttf',
  'terms.stats',
  'terms.raw',
  'terms.maxcount',
  'terms.regex.flag',
];

export const request: MicroTransform<RequestContext> = {
  name: 'terms-component',
  match: (ctx) => ctx.endpoint === 'terms' || ctx.params.get('terms') === 'true',
  apply: (ctx) => {
    const p = ctx.params;

    // Rewrite URI: /solr/{collection}/terms → /{collection}/_search
    if (ctx.endpoint === 'terms') {
      ctx.msg.set('URI', `/${ctx.collection}/_search`);
      ctx.msg.set('method', 'POST');
      const headers = ctx.msg.get('headers');
      if (headers) {
        headers.set('content-type', 'application/json');
      }
    }

    // No search results needed — only aggregations
    ctx.body.set('size', 0);
    ctx.body.set('query', new Map([['match_all', new Map()]]));

    // Parse terms.fl — can be specified multiple times (comma-separated)
    const termsFlRaw = p.get('terms.fl');
    if (!termsFlRaw) return;

    const fields = termsFlRaw.split(',').map((f) => f.trim()).filter(Boolean);
    const aggs = new Map<string, unknown>();

    for (const field of fields) {
      const termAgg = new Map<string, unknown>();

      // Field name — keyword fields work best; text fields need .keyword suffix
      // For now, use the field as-is; schema-aware suffix can be added later
      termAgg.set('field', field);

      // Size (terms.limit)
      const limit = p.get('terms.limit');
      termAgg.set('size', limit ? parseInt(limit, 10) : 10);

      // Min doc count (terms.mincount)
      const mincount = p.get('terms.mincount');
      if (mincount) {
        termAgg.set('min_doc_count', parseInt(mincount, 10));
      }

      // Sort: terms.sort=count (default) or terms.sort=index (alphabetical)
      const sort = p.get('terms.sort');
      if (sort === 'index') {
        termAgg.set('order', new Map([['_key', 'asc']]));
      }
      // Default is by count desc, which is also OpenSearch's default

      // Prefix filter → include regex
      const prefix = p.get('terms.prefix');
      if (prefix) {
        termAgg.set('include', `${prefix}.*`);
      }

      // Regex filter
      const regex = p.get('terms.regex');
      if (regex) {
        termAgg.set('include', regex);
      }

      // Specific term lookup (terms.list)
      const termsList = p.get('terms.list');
      if (termsList) {
        const termsArray = termsList.split(',').map((t) => t.trim());
        termAgg.set('include', termsArray);
      }

      aggs.set(`${field}_terms`, new Map([['terms', termAgg]]));
    }

    ctx.body.set('aggs', aggs);

    // Warn on unsupported params
    for (const param of UNSUPPORTED_PARAMS) {
      if (p.has(param)) {
        console.warn(`[terms-component] Unsupported Solr param '${param}' — no OpenSearch equivalent, skipped.`);
      }
    }
  },
};

export const response: MicroTransform<ResponseContext> = {
  name: 'terms-component',
  match: (ctx) => ctx.endpoint === 'terms' || ctx.requestParams.get('terms') === 'true',
  apply: (ctx) => {
    const aggregations: JavaMap | undefined = ctx.responseBody.get('aggregations');
    if (!aggregations) return;

    const useListFormat = ctx.requestParams.has('terms.list');
    const termsResult = new Map<string, unknown>();

    // Parse terms.fl to know the original field names
    const termsFlRaw = ctx.requestParams.get('terms.fl');
    if (!termsFlRaw) return;

    const fields = termsFlRaw.split(',').map((f) => f.trim()).filter(Boolean);

    for (const field of fields) {
      const aggResult: JavaMap | undefined = aggregations.get(`${field}_terms`);
      if (!aggResult) continue;

      const buckets: JavaMap[] = aggResult.get('buckets');
      if (!buckets) continue;

      if (useListFormat) {
        // terms.list → object format: { term: count, term: count }
        const obj = new Map<string, number>();
        for (const bucket of buckets) {
          obj.set(bucket.get('key'), bucket.get('doc_count'));
        }
        termsResult.set(field, obj);
      } else {
        // Default → flat alternating array: [term, count, term, count, ...]
        const flat: (string | number)[] = [];
        for (const bucket of buckets) {
          flat.push(bucket.get('key'));
          flat.push(bucket.get('doc_count'));
        }
        termsResult.set(field, flat);
      }
    }

    // Build Solr-format response
    ctx.responseBody.set('terms', termsResult);

    // Synthesize responseHeader
    const params = new Map<string, string>();
    ctx.requestParams.forEach((v, k) => params.set(k, v));
    ctx.responseBody.set(
      'responseHeader',
      new Map<string, unknown>([
        ['status', 0],
        ['QTime', 0],
        ['params', params],
      ]),
    );

    // Remove OpenSearch-specific fields
    ctx.responseBody.delete('hits');
    ctx.responseBody.delete('aggregations');
    ctx.responseBody.delete('took');
    ctx.responseBody.delete('timed_out');
    ctx.responseBody.delete('_shards');
    ctx.responseBody.delete('terminated_early');
  },
};
