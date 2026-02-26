/**
 * Transform registry — single source of truth for all feature registrations.
 *
 * To add a new feature:
 *   1. Create features/my-feature.ts exporting { request?, response? }
 *   2. Import it here
 *   3. Add to the appropriate endpoint group
 */
import type { TransformRegistry } from './pipeline';
import type { RequestContext, ResponseContext } from './context';

import * as selectUri from './features/select-uri';
import * as queryQ from './features/query-q';
import * as filterQuery from './features/filter-query';
import * as fieldList from './features/field-list';
import * as sort from './features/sort';
import * as pagination from './features/pagination';
import * as facets from './features/facets';
import * as hitsToDocs from './features/hits-to-docs';
import * as multiValued from './features/multi-valued';
import * as responseHeader from './features/response-header';
import * as responseWriter from './features/response-writer';

export const requestRegistry: TransformRegistry<RequestContext> = {
  global: [],
  byEndpoint: {
    select: [
      selectUri.request,       // URI rewrite — must be first
      queryQ.request,          // q=... → query DSL
      filterQuery.request,     // fq=... → bool filter (runs after query-q)
      fieldList.request,       // fl=... → _source
      sort.request,            // sort=... → sort clause
      pagination.request,      // start/rows → from/size
      facets.request,          // facet params → aggregations
    ],
  },
};

export const responseRegistry: TransformRegistry<ResponseContext> = {
  global: [],
  byEndpoint: {
    select: [
      hitsToDocs.response,       // hits.hits → response.docs (must be first)
      multiValued.response,      // scalar → array wrapping
      fieldList.response,        // filter docs to fl fields
      pagination.response,       // set response.start
      facets.response,           // aggregations → facet_counts
      responseHeader.response,   // synthesize responseHeader
      responseWriter.response,   // set content-type for wt
    ],
  },
};
