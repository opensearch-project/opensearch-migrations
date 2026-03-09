/**
 * Parser selector — maps defType to the appropriate parser function.
 *
 * Returns `parseLucene` for lucene/undefined, `null` for unsupported types.
 * The translator handles eDisMax as a special case (different signature)
 * and falls back to query_string passthrough for null.
 *
 * Requirements: 3.1, 6.2, 6.3
 */

import type { Token } from '../lexer/lexer';
import type { ParseResult } from './luceneParser';
import { parseLucene } from './luceneParser';

export type ParserFn = (tokens: Token[], df: string) => ParseResult;

/** Select parser based on defType. Returns null for unsupported defType. */
export function selectParser(defType: string | undefined): ParserFn | null {
  switch (defType) {
    case undefined:
    case 'lucene':
      return parseLucene;
    case 'edismax':
      // eDisMax has a different signature (takes EDisMaxConfig instead of df).
      // The translator handles eDisMax specially — return parseLucene as the
      // base parser; the translator will call parseEdismax directly.
      return parseLucene;
    default:
      return null;
  }
}
