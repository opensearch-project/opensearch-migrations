/**
 * Field list (fl) — Solr fl param → OpenSearch _source filtering.
 *
 * Request: fl=id,title → _source: ["id", "title"]
 * Response: filter response docs to only include requested fields.
 */
import type { MicroTransform } from '../pipeline';
import type { RequestContext, ResponseContext } from '../context';

function parseFieldList(fl: string): string[] {
  return fl.split(',').map(f => f.trim()).filter(Boolean);
}

export const request: MicroTransform<RequestContext> = {
  name: 'field-list',
  match: (ctx) => ctx.params.has('fl') && ctx.params.get('fl') !== '*',
  apply: (ctx) => {
    const fields = parseFieldList(ctx.params.get('fl')!);
    ctx.body._source = fields;
  },
};

export const response: MicroTransform<ResponseContext> = {
  name: 'field-list',
  match: (ctx) => {
    const fl = ctx.requestParams.get('fl');
    return !!fl && fl !== '*' && !!(ctx.responseBody.response as any)?.docs;
  },
  apply: (ctx) => {
    const fields = parseFieldList(ctx.requestParams.get('fl')!);
    const resp = ctx.responseBody.response as any;
    resp.docs = resp.docs.map((doc: Record<string, unknown>) => {
      const filtered: Record<string, unknown> = {};
      for (const f of fields) {
        if (f in doc) filtered[f] = doc[f];
      }
      return filtered;
    });
  },
};
