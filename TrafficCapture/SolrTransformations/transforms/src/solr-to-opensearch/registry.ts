/**
 * Transform registry — single source of truth for all feature registrations.
 *
 * To add a new feature:
 *   1. Create features/my-feature.ts exporting { request?, response?, params?, paramPrefixes? }
 *   2. Import it here
 *   3. Add to the appropriate endpoint group
 *   4. Add to FEATURE_MODULES so validation auto-discovers its params
 */
import type { TransformRegistry } from './pipeline';
import type { RequestContext, ResponseContext } from './context';

import * as solrconfigDefaults from './features/solrconfig-defaults';
import * as jsonRequest from './features/json-request';
import * as selectUri from './features/select-uri';
import * as queryQ from './features/query-q';
import * as filterQueryFq from './features/filter-query-fq';
import * as boostQueryBq from './features/boost-query-bq';
import * as cursorPagination from './features/cursor-pagination';
import * as fieldList from './features/field-list';
import * as sort from './features/sort';
import * as jsonFacets from './features/json-facets';
import * as minimumMatch from './features/minimum-match';
import * as highlighting from './features/highlighting';
import * as hitsToDocs from './features/hits-to-docs';
import * as aggsToFacets from './features/aggs-to-facets';
import * as responseHeader from './features/response-header';
import * as updateRouter from './features/update-router';
import * as validation from './features/validation';
import { initValidation } from './features/validation';
import type { ParamRule } from './features/validation';

/** Shape of a feature module that declares supported params for validation. */
export interface FeatureModule {
  params?: string[];
  paramPrefixes?: string[];
  paramRules?: ParamRule[];
}

/**
 * All feature modules — used to auto-aggregate supported params and rules.
 * hitsToDocs and aggsToFacets are response-only transforms with no request
 * params, so they don't need to be listed here for validation discovery.
 */
const FEATURE_MODULES: FeatureModule[] = [
  selectUri, queryQ, filterQueryFq, boostQueryBq, cursorPagination, fieldList,
  sort, jsonFacets, highlighting, minimumMatch,
];

/**
 * Common Solr params allowlisted for validation — not rejected as unsupported.
 * - wt: only json is returned; other formats (xml, csv) are not translated
 * - indent: pretty-print flag, ignored by the shim
 * - echoParams: param echo in response header, not implemented
 * - df: actively used by the query parser as the default field
 */
const COMMON_PARAMS = ['wt', 'indent', 'echoParams', 'df'];

/** Auto-aggregated from all feature modules. */
export const supportedParams = new Set<string>(COMMON_PARAMS);
export const supportedPrefixes: string[] = [];
const aggregatedRules: ParamRule[] = [];

for (const mod of FEATURE_MODULES) {
  mod.params?.forEach((p) => supportedParams.add(p));
  if (mod.paramPrefixes) supportedPrefixes.push(...mod.paramPrefixes);
  if (mod.paramRules) aggregatedRules.push(...mod.paramRules);
}

initValidation(supportedParams, supportedPrefixes, aggregatedRules);

export const requestRegistry: TransformRegistry<RequestContext> = {
  global: [
    validation.request, // Fail fast on null/empty inputs and unsupported params
  ],
  byEndpoint: {
    select: [
      jsonRequest.request, // JSON body → URL params (before defaults so body values are visible)
      solrconfigDefaults.request, // Apply solrconfig.xml defaults/invariants
      selectUri.request, // URI rewrite
      queryQ.request, // q=... → query DSL
      minimumMatch.request, // mm → minimum_should_match (after query-q builds bool)
      filterQueryFq.request, // fq=... → bool.filter (after query-q)
      boostQueryBq.request, // bq=... → bool.should (after fq, dismax/edismax only)
      cursorPagination.request, // cursorMark → search_after (after query-q sets from)
      jsonFacets.request, // json.facet → aggs
      fieldList.request, // fl=... → _source
      highlighting.request, // hl=true → highlight block
      sort.request, // sort=... → sort DSL
    ],
    update: [
      updateRouter.request,   // single entry point: routes /update/* by path and body shape
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
    update: [
      updateRouter.response,  // generic _doc response → Solr format (create/update/delete)
    ],
  },
};
