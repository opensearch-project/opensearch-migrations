/**
 * Field list (fl) parameter — convert Solr fl param to OpenSearch _source.
 *
 * Handles:
 *   - fl=* or absent → no _source filter (return all fields)
 *   - fl=field1,field2 → _source: ["field1", "field2"]
 *   - fl=field1 field2 → _source: ["field1", "field2"]
 *   - fl=id na* price → _source: ["id", "na*", "price"] (glob patterns passed through)
 *   - Filters out pseudo-fields like "score" (not stored in _source)
 *
 * Request-only. All output is Maps/arrays for zero-serialization GraalVM interop.
 */
import type { MicroTransform } from '../pipeline';
import type { RequestContext } from '../context';
import type { ParamRule } from './validation';

/** Solr query params this feature handles. */
export const params = ['fl'];
export const paramRules: ParamRule[] = [
  { name: 'fl', type: 'rejectPattern', pattern: String.raw`\{!`, reason: 'Local params ({!...}) syntax in fl is not supported' },
];
// Pseudo-fields that don't exist in _source (standalone * means all fields)
const PSEUDO_FIELDS = new Set(['score', '*']);

function parseFieldList(fl: string | null): string[] | null {
  if (!fl || fl.trim() === '*') return null; // all fields

  // Solr accepts comma or space separated
  const fields = fl
    .split(/[,\s]+/)
    .map((f) => f.trim())
    .filter((f) => {
      if (f?.startsWith('[')) {
        console.warn(`[field-list] Unsupported Solr document transformer ignored: ${f}`);
        return false;
      }
      return f && !PSEUDO_FIELDS.has(f);
    });

  return fields.length > 0 ? fields : null;
}

export const request: MicroTransform<RequestContext> = {
  name: 'field-list',
  apply: (ctx) => {
    const fl = ctx.params.get('fl');
    const fields = parseFieldList(fl);
    if (fields) {
      ctx.body.set('_source', fields);
    }
  },
};
