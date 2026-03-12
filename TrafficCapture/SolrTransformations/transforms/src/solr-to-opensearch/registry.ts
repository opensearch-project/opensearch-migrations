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
import * as highlighting from './features/highlighting';
import * as termsComponent from './features/terms-component';
import * as hitsToDocs from './features/hits-to-docs';
import * as responseHeader from './features/response-header';

export const requestRegistry: TransformRegistry<RequestContext> = {
  global: [],
  byEndpoint: {
    select: [
      selectUri.request, // URI rewrite — must be first
      queryQ.request, // q=... → query DSL
      highlighting.request, // hl=true → highlight block
    ],
    terms: [
      termsComponent.request, // /terms → match_all + terms agg
    ],
  },
};

export const responseRegistry: TransformRegistry<ResponseContext> = {
  global: [],
  byEndpoint: {
    select: [
      highlighting.response, // per-hit highlight → top-level highlighting section (must run before hits-to-docs)
      hitsToDocs.response, // hits.hits → response.docs
      responseHeader.response, // synthesize responseHeader
    ],
    terms: [
      termsComponent.response, // aggregation buckets → Solr terms format
    ],
  },
};
