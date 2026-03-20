/**
 * Cursor pagination — cursorMark → search_after translation.
 *
 * Request: Detects cursorMark param, decodes to search_after sort values,
 *          ensures sort includes _id tiebreaker.
 * Response: Encodes last hit's sort values into nextCursorMark token.
 *
 * Uses base64(JSON) encoding for the cursor token — simple, deterministic,
 * and doesn't need to be compatible with actual Solr's internal format.
 */
import type { MicroTransform } from '../pipeline';
import type { RequestContext, ResponseContext, JavaMap } from '../context';

const CURSOR_MARK_START = '*';

const B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

/** Pure JS base64 encode (no btoa/Buffer/Java dependency). */
function b64Encode(str: string): string {
  let out = '';
  for (let i = 0; i < str.length; i += 3) {
    const a = str.codePointAt(i)!;
    const b = i + 1 < str.length ? str.codePointAt(i + 1)! : 0;
    const c = i + 2 < str.length ? str.codePointAt(i + 2)! : 0;
    out += B64[a >> 2] + B64[((a & 3) << 4) | (b >> 4)];
    out += i + 1 < str.length ? B64[((b & 15) << 2) | (c >> 6)] : '=';
    out += i + 2 < str.length ? B64[c & 63] : '=';
  }
  return out;
}

/** Pure JS base64 decode. */
function b64Decode(encoded: string): string {
  let out = '';
  const clean = encoded.replaceAll(/=+$/g, '');
  for (let i = 0; i < clean.length; i += 4) {
    const a = B64.indexOf(clean[i]);
    const b = B64.indexOf(clean[i + 1]);
    const c = i + 2 < clean.length ? B64.indexOf(clean[i + 2]) : 0;
    const d = i + 3 < clean.length ? B64.indexOf(clean[i + 3]) : 0;
    out += String.fromCodePoint((a << 2) | (b >> 4));
    if (i + 2 < clean.length) out += String.fromCodePoint(((b & 15) << 4) | (c >> 2));
    if (i + 3 < clean.length) out += String.fromCodePoint(((c & 3) << 6) | d);
  }
  return out;
}

function encodeCursorMark(sortValues: any[]): string {
  return b64Encode(JSON.stringify(sortValues));
}

function decodeCursorMark(token: string): any[] {
  return JSON.parse(b64Decode(token));
}

/**
 * Parse Solr sort string into OpenSearch sort array.
 * "price asc, id desc" → [{"price": "asc"}, {"id": "desc"}]
 * Maps Solr's uniqueKey field "id" to OpenSearch's "_id".
 */
function parseSolrSort(sortStr: string): JavaMap[] {
  // Handle + as space (URL encoding not always decoded by shim's URLSearchParams polyfill)
  return sortStr.replaceAll('+', ' ').split(',').map((clause) => {
    const [field, dir] = clause.trim().split(/\s+/);
    const osField = field === 'id' ? '_id' : field;
    return new Map([[osField, (dir || 'asc').toLowerCase()]]);
  });
}

/** Check if sort array already includes _id as a tiebreaker. */
function hasTiebreaker(sortArray: JavaMap[]): boolean {
  return sortArray.some((s) => s.has('_id'));
}

// Exported for testing
export { b64Encode, b64Decode, encodeCursorMark, decodeCursorMark, parseSolrSort, hasTiebreaker };

export const request: MicroTransform<RequestContext> = {
  name: 'cursor-pagination',
  match: (ctx) => {
    if (!ctx.params.has('cursorMark')) return false;
    if (ctx.dualMode) {
      console.warn('[cursor-pagination] cursorMark is not supported in dual-mode — use start/rows for pagination during dual-target validation.');
      // TODO: implement offset-based fallback for dual-mode if needed
      return false;
    }
    return true;
  },
  apply: (ctx) => {
    const cursorMark = ctx.params.get('cursorMark')!;

    // cursorMark and start are mutually exclusive — remove from if present
    ctx.body.delete('from');

    // Parse and set sort with _id tiebreaker
    const sortStr = ctx.params.get('sort');
    if (sortStr) {
      const sortArray = parseSolrSort(sortStr);
      if (!hasTiebreaker(sortArray)) {
        sortArray.push(new Map([['_id', 'asc']]));
      }
      ctx.body.set('sort', sortArray);
    } else {
      // No sort specified — default to _id asc (deterministic ordering required)
      ctx.body.set('sort', [new Map([['_id', 'asc']])]);
    }

    // Decode cursor mark into search_after (skip for initial request)
    if (cursorMark !== CURSOR_MARK_START) {
      ctx.body.set('search_after', decodeCursorMark(cursorMark));
    }
  },
};

export const response: MicroTransform<ResponseContext> = {
  name: 'cursor-pagination',
  match: (ctx) => {
    if (!ctx.requestParams.has('cursorMark')) return false;
    if (ctx.dualMode) return false;
    return true;
  },
  apply: (ctx) => {
    // Must run BEFORE hits-to-docs since we need the raw hits.hits[].sort array.
    // We extract nextCursorMark here; hits-to-docs will later build response.start=0.
    const hits: JavaMap | undefined = ctx.responseBody.get('hits');
    const hitsArray: JavaMap[] | undefined = hits?.get('hits');

    if (hitsArray && hitsArray.length > 0) {
      const lastHit = hitsArray.at(-1)!;
      const sortValues: any[] = lastHit.get('sort');
      if (sortValues) {
        ctx.responseBody.set('nextCursorMark', encodeCursorMark(Array.from(sortValues)));
        return;
      }
    }

    // No results or no sort values — return the same cursorMark (end signal)
    // Re-encode since requestParams.get() returns the decoded value
    const originalMark = ctx.requestParams.get('cursorMark')!;
    if (originalMark === CURSOR_MARK_START) {
      ctx.responseBody.set('nextCursorMark', CURSOR_MARK_START);
    } else {
      ctx.responseBody.set('nextCursorMark', encodeCursorMark(decodeCursorMark(originalMark)));
    }
  },
};
