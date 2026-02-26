/**
 * Facets — Solr facet params → OpenSearch aggregations.
 *
 * Request: facet=true&facet.field=category&facet.field=status → aggregations
 * Response: aggregations → Solr facet_counts format
 *
 * Supports:
 *   - facet.field → terms aggregation
 *   - facet.limit → size on terms agg
 *   - facet.mincount → min_doc_count
 *   - facet.sort → order on terms agg
 *   - facet.query → filter aggregation
 */
import type { MicroTransform } from '../pipeline';
import type { RequestContext, ResponseContext } from '../context';

export const request: MicroTransform<RequestContext> = {
  name: 'facets',
  match: (ctx) => ctx.params.get('facet') === 'true',
  apply: (ctx) => {
    const aggs: Record<string, unknown> = {};
    const limit = parseInt(ctx.params.get('facet.limit') || '100', 10);
    const mincount = parseInt(ctx.params.get('facet.mincount') || '0', 10);
    const sortParam = ctx.params.get('facet.sort');

    // facet.field → terms aggregation
    for (const field of ctx.params.getAll('facet.field')) {
      const agg: Record<string, unknown> = { field, size: limit };
      if (mincount > 0) agg.min_doc_count = mincount;
      if (sortParam === 'index') {
        agg.order = { _key: 'asc' };
      }
      aggs[field] = { terms: agg };
    }

    // facet.query → filter aggregation
    for (const fq of ctx.params.getAll('facet.query')) {
      aggs['facet_query_' + fq] = { filter: { query_string: { query: fq } } };
    }

    if (Object.keys(aggs).length > 0) ctx.body.aggs = aggs;
  },
};

export const response: MicroTransform<ResponseContext> = {
  name: 'facets',
  match: (ctx) => ctx.requestParams.get('facet') === 'true' && !!(ctx.responseBody as any).aggregations,
  apply: (ctx) => {
    const aggs = (ctx.responseBody as any).aggregations as Record<string, any>;
    const facetFields: Record<string, unknown[]> = {};
    const facetQueries: Record<string, number> = {};

    for (const [name, agg] of Object.entries(aggs)) {
      if (name.startsWith('facet_query_')) {
        // filter aggregation → facet_queries
        facetQueries[name.slice('facet_query_'.length)] = agg.doc_count || 0;
      } else if (agg.buckets) {
        // terms aggregation → facet_fields (flat array: [key, count, key, count, ...])
        const flat: unknown[] = [];
        for (const bucket of agg.buckets) {
          flat.push(bucket.key, bucket.doc_count);
        }
        facetFields[name] = flat;
      }
    }

    ctx.responseBody.facet_counts = {
      facet_queries: facetQueries,
      facet_fields: facetFields,
      facet_ranges: {},
      facet_intervals: {},
      facet_heatmaps: {},
    };
    delete (ctx.responseBody as any).aggregations;
  },
};
