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

import * as solrconfigDefaults from './features/solrconfig-defaults';
import * as selectUri from './features/select-uri';
import * as queryQ from './features/query-q';
import * as cursorPagination from './features/cursor-pagination';
import * as fieldList from './features/field-list';
import * as sort from './features/sort';
import * as jsonFacets from './features/json-facets';
import * as highlighting from './features/highlighting';
import * as hitsToDocs from './features/hits-to-docs';
import * as aggsToFacets from './features/aggs-to-facets';
import * as responseHeader from './features/response-header';

export const requestRegistry: TransformRegistry<RequestContext> = {
  global: [],
  byEndpoint: {
    select: [
      solrconfigDefaults.request, // Apply solrconfig.xml defaults/invariants — must be before all others
      selectUri.request, // URI rewrite
      queryQ.request, // q=... → query DSL
      cursorPagination.request, // cursorMark → search_after (after query-q sets from)
      jsonFacets.request, // json.facet → aggs
      fieldList.request, // fl=... → _source
      highlighting.request, // hl=true → highlight block
      sort.request, // sort=... → sort DSL
      jsonFacets.request, // json.facet → aggs
    ],
  },
};

export const responseRegistry: TransformRegistry<ResponseContext> = {
  global: [],
  byEndpoint: {
    select: [
      cursorPagination.response, // nextCursorMark from last hit (before hits deleted)
      highlighting.response, // per-hit highlight → top-level highlighting section (must run before hits-to-docs)
      hitsToDocs.response, // hits.hits → response.docs
      aggsToFacets.response, // aggregations → facets
      responseHeader.response, // synthesize responseHeader
    ],
  },
};
