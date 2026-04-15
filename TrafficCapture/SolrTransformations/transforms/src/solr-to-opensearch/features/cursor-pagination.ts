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

/** Solr query params this feature handles. */
export const params = ['cursorMark'];

const CURSOR_MARK_START = '*';

/** Solr uniqueKey field name — maps to OpenSearch's _id. */
const SOLR_UNIQUE_KEY = 'id';
const OS_UNIQUE_KEY = '_id';

const B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

/** UTF-8 encode a string into a byte array (GraalVM-safe, no TextEncoder). */
function toUTF8Bytes(str: string): number[] {
  const bytes: number[] = [];
  let i = 0;
  while (i < str.length) {
    const code = str.codePointAt(i)!;
    // Codepoints above U+FFFF use a surrogate pair (2 UTF-16 code units), so advance by 2
    const step = code >= 0x10000 ? 2 : 1;
    if (code < 0x80) {
      bytes.push(code);
    } else if (code < 0x800) {
      bytes.push(0xc0 | (code >> 6), 0x80 | (code & 0x3f));
    } else if (code < 0x10000) {
      bytes.push(0xe0 | (code >> 12), 0x80 | ((code >> 6) & 0x3f), 0x80 | (code & 0x3f));
    } else {
      bytes.push(0xf0 | (code >> 18), 0x80 | ((code >> 12) & 0x3f), 0x80 | ((code >> 6) & 0x3f), 0x80 | (code & 0x3f));
    }
    i += step;
  }
  return bytes;
}

/** Decode a UTF-8 byte array back to a string. */
function fromUTF8Bytes(bytes: number[]): string {
  let out = '';
  for (let i = 0; i < bytes.length;) {
    const b = bytes[i];
    if (b < 0x80) { out += String.fromCodePoint(b); i++; }
    else if (b < 0xe0) { out += String.fromCodePoint(((b & 0x1f) << 6) | (bytes[i + 1] & 0x3f)); i += 2; }
    else if (b < 0xf0) { out += String.fromCodePoint(((b & 0x0f) << 12) | ((bytes[i + 1] & 0x3f) << 6) | (bytes[i + 2] & 0x3f)); i += 3; }
    else { out += String.fromCodePoint(((b & 0x07) << 18) | ((bytes[i + 1] & 0x3f) << 12) | ((bytes[i + 2] & 0x3f) << 6) | (bytes[i + 3] & 0x3f)); i += 4; }
  }
  return out;
}

/** Pure JS base64 encode with UTF-8 support (no btoa/Buffer/Java dependency). */
function b64Encode(str: string): string {
  const bytes = toUTF8Bytes(str);
  let out = '';
  for (let i = 0; i < bytes.length; i += 3) {
    const a = bytes[i];
    const b = i + 1 < bytes.length ? bytes[i + 1] : 0;
    const c = i + 2 < bytes.length ? bytes[i + 2] : 0;
    out += B64[a >> 2] + B64[((a & 3) << 4) | (b >> 4)];
    out += i + 1 < bytes.length ? B64[((b & 15) << 2) | (c >> 6)] : '=';
    out += i + 2 < bytes.length ? B64[c & 63] : '=';
  }
  return out;
}

/** Pure JS base64 decode with UTF-8 support. */
function b64Decode(encoded: string): string {
  const clean = encoded.replaceAll(/=+$/g, '');
  const bytes: number[] = [];
  for (let i = 0; i < clean.length; i += 4) {
    const a = B64.indexOf(clean[i]);
    const b = B64.indexOf(clean[i + 1]);
    const c = i + 2 < clean.length ? B64.indexOf(clean[i + 2]) : 0;
    const d = i + 3 < clean.length ? B64.indexOf(clean[i + 3]) : 0;
    bytes.push((a << 2) | (b >> 4));
    if (i + 2 < clean.length) bytes.push(((b & 15) << 4) | (c >> 2));
    if (i + 3 < clean.length) bytes.push(((c & 3) << 6) | d);
  }
  return fromUTF8Bytes(bytes);
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
  // parseParams() in context.ts already decodes + as space, so no manual
  // replacement needed here.
  return sortStr.split(',').map((clause) => {
    const [field, dir] = clause.trim().split(/\s+/);
    const osField = field === SOLR_UNIQUE_KEY ? OS_UNIQUE_KEY : field;
    return new Map([[osField, (dir || 'asc').toLowerCase()]]);
  });
}

/** Check if sort array already includes _id as a tiebreaker. */
function hasTiebreaker(sortArray: JavaMap[]): boolean {
  return sortArray.some((s) => s.has(OS_UNIQUE_KEY));
}

// Exported for testing
export { b64Encode, b64Decode, encodeCursorMark, decodeCursorMark, parseSolrSort, hasTiebreaker,
  SOLR_UNIQUE_KEY, OS_UNIQUE_KEY };

export const request: MicroTransform<RequestContext> = {
  name: 'cursor-pagination',
  match: (ctx) => ctx.params.has('cursorMark'),
  apply: (ctx) => {
    const cursorMark = ctx.params.get('cursorMark')!;

    // cursorMark and start are mutually exclusive — remove from if present
    ctx.body.delete('from');

    // Parse and set sort with _id tiebreaker
    const sortStr = ctx.params.get('sort');
    if (sortStr) {
      const sortArray = parseSolrSort(sortStr);
      if (!hasTiebreaker(sortArray)) {
        sortArray.push(new Map([[OS_UNIQUE_KEY, 'asc']]));
      }
      ctx.body.set('sort', sortArray);
    } else {
      // No sort specified — default to _id asc (deterministic ordering required)
      ctx.body.set('sort', [new Map([[OS_UNIQUE_KEY, 'asc']])]);
    }

    // Decode cursor mark into search_after (skip for initial request)
    if (cursorMark !== CURSOR_MARK_START) {
      try {
        ctx.body.set('search_after', decodeCursorMark(cursorMark));
      } catch {
        throw new Error(`Invalid cursorMark token: ${cursorMark}`);
      }
    }
  },
};

export const response: MicroTransform<ResponseContext> = {
  name: 'cursor-pagination',
  match: (ctx) => ctx.requestParams.has('cursorMark'),
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
