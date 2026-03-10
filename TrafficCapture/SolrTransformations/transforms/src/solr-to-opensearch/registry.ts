/**
 * Transform registry — single source of truth for all feature registrations.
 *
 * Transforms are grouped by Solr endpoint (select, update, admin, etc.) for
 * O(1) dispatch. A /select request only runs select transforms.
 *
 * ORDERING MATTERS: transforms run sequentially in array order.
 * - select-uri must be first (rewrites URI for downstream transforms)
 * - query-q must run before filter-fq (filter-fq wraps the existing query)
 * - sort, pagination, field-list are independent of each other
 *
 * To add a new feature:
 *   1. Create features/my-feature.ts exporting { request?, response? }
 *   2. Import it here
 *   3. Add to the appropriate endpoint group in the correct position
 */
import type { TransformRegistry } from './pipeline';
import type { RequestContext, ResponseContext } from './context';

import * as selectUri from './features/select-uri';
import * as queryQ from './features/query-q';
import * as filterFq from './features/filter-fq';
import * as sort from './features/sort';
import * as pagination from './features/pagination';
import * as fieldList from './features/field-list';
import * as hitsToDocs from './features/hits-to-docs';
import * as responseHeader from './features/response-header';

export const requestRegistry: TransformRegistry<RequestContext> = {
  global: [],
  byEndpoint: {
    select: [
      selectUri.request, // URI rewrite — must be first
      queryQ.request, // q=... → query DSL
      filterFq.request, // fq=... → bool.filter clauses
      sort.request, // sort=... → sort array
      pagination.request, // start/rows → from/size
      fieldList.request, // fl=... → _source
    ],
  },
};

export const responseRegistry: TransformRegistry<ResponseContext> = {
  global: [],
  byEndpoint: {
    select: [
      hitsToDocs.response, // hits.hits → response.docs
      responseHeader.response, // synthesize responseHeader
    ],
  },
};
