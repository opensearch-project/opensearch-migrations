/**
 * Minimum match — translate Solr `mm` param to OpenSearch `minimum_should_match`.
 *
 * Solr and OpenSearch share identical syntax for this parameter (integers,
 * percentages, conditional expressions), so the value is a direct passthrough.
 *
 * Only applies when defType is dismax or edismax — the standard (lucene)
 * query parser ignores mm, and so do we to match Solr behavior.
 *
 * Must run AFTER query-q so the query DSL bool is already in ctx.body.
 */
import type { MicroTransform } from '../pipeline';
import type { RequestContext } from '../context';
import type { ParamRule } from './validation';

export const params = ['mm'];

export const paramRules: ParamRule[] = [
  {
    name: 'mm',
    type: 'rejectPattern',
    pattern: String.raw`[^-\d%<\s]`,
    reason: 'Invalid mm syntax — only digits, -, %, <, and spaces are allowed (e.g., "2", "75%", "-1", "3<90%", "2<-25% 9<-3")',
  },
];

const DISMAX_TYPES = new Set(['dismax', 'edismax']);

export const request: MicroTransform<RequestContext> = {
  name: 'minimum-match',
  match: (ctx) => ctx.params.has('mm') && DISMAX_TYPES.has(ctx.params.get('defType') ?? ''),
  apply: (ctx) => {
    const query = ctx.body.get('query');
    if (!query || typeof query.get !== 'function') return;

    const boolClause = query.get('bool');
    if (!boolClause || typeof boolClause.set !== 'function') return;

    boolClause.set('minimum_should_match', ctx.params.get('mm'));
  },
};
