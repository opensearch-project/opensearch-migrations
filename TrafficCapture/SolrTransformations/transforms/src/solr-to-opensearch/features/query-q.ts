/**
 * Query q parameter — convert Solr q param to OpenSearch query DSL.
 *
 * Delegates to the structured Lexer → Parser → AST → Transformer pipeline
 * via translateQ. Falls back to query_string passthrough on any error.
 *
 * Request-only. All output is Maps for zero-serialization GraalVM interop.
 *
 * Requirements: 7.1, 7.2, 7.3, 7.4, 7.5, 9.3, 9.4
 */
import type { MicroTransform } from '../pipeline';
import type { RequestContext } from '../context';
import { translateQ } from '../translator/translateQ';

export const request: MicroTransform<RequestContext> = {
  name: 'query-q',
  apply: (ctx) => {
    const q = ctx.params.get('q') || '*:*';
    const defType = ctx.params.get('defType') ?? undefined;
    const qf = ctx.params.get('qf') ?? undefined;
    const pf = ctx.params.get('pf') ?? undefined;
    const df = ctx.params.get('df') ?? undefined;

    const result = translateQ({ q, defType, qf, pf, df });
    ctx.body.set('query', result.dsl);

    if (result.warnings.length > 0) {
      ctx.body.set('_solr_warnings', result.warnings);
    }
  },
};
