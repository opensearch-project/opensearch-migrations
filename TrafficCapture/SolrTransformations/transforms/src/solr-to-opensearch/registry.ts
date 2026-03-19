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
import * as fieldList from './features/field-list';
import * as jsonFacets from './features/json-facets';
import * as hitsToDocs from './features/hits-to-docs';
import * as aggsToFacets from './features/aggs-to-facets';
import * as responseHeader from './features/response-header';

export const requestRegistry: TransformRegistry<RequestContext> = {
  global: [],
  byEndpoint: {
    select: [
      selectUri.request, // URI rewrite — must be first
      queryQ.request, // q=... → query DSL
      jsonFacets.request, // json.facet → aggs,
      fieldList.request, // fl=... → _source
    ],
  },
};

export const responseRegistry: TransformRegistry<ResponseContext> = {
  global: [],
  byEndpoint: {
    select: [
      hitsToDocs.response, // hits.hits → response.docs
      aggsToFacets.response, // aggregations → facets
      responseHeader.response, // synthesize responseHeader
    ],
  },
};
