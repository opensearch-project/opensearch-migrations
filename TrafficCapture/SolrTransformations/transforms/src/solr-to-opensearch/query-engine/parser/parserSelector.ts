/**
 * Parser selector — maps Solr's `defType` parameter to the appropriate parser.
 *
 * Solr supports multiple query parser types (lucene, edismax, dismax, etc.)
 * selected via the `defType` request parameter. This module resolves the
 * defType string to a parser function, or returns null for unsupported types.
 *
 * The selector only handles defType routing. Each parser is responsible for
 * extracting its own configuration from the request params.
 */

import type { Token } from '../lexer/lexer';
import type { ParseResult } from './types';

/**
 * A parser function that takes a token stream and request parameters.
 * Each parser extracts the params it needs internally (e.g., `df`, `qf`, `pf`).
 */
export type ParserFn = (tokens: Token[], params: ReadonlyMap<string, string>) => ParseResult;

/**
 * Select parser based on defType.
 * @returns Parser function, or null for unsupported defType.
 */
export function selectParser(defType: string | undefined): ParserFn | null {
  // TODO: implement
  throw new Error('Not implemented');
}
