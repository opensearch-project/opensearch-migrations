/**
 * Solr query parser.
 *
 * Parses a Solr query string into an AST matching the types in
 * ../ast/nodes.ts.
 *
 * Responsibilities:
 *   - Parse the query string into an AST
 *   - Resolve default fields (df) on bare values and phrases
 *   - Return parse errors as ParseError (never throws)
 */

import type { ParseResult } from './types';

/**
 * Parse a Solr query string into an AST.
 *
 * @param query - The raw Solr query string (the `q` parameter value)
 * @param params - Request parameters. Reads `df` to resolve bare
 *                 values and unfielded phrases to a default field.
 * @returns ParseResult with the AST and any errors
 *
 * Example:
 *   parseSolrQuery("title:java AND price:[10 TO 100]", new Map([["df", "content"]]))
 *   → { ast: BoolNode { and: [FieldNode, RangeNode] }, errors: [] }
 *
 *   parseSolrQuery("java", new Map([["df", "title"]]))
 *   → { ast: FieldNode { field: "title", value: "java" }, errors: [] }
 */
export function parseSolrQuery(
  query: string,
  params: ReadonlyMap<string, string>,
): ParseResult {
  // TODO: implement
  // 1. Parse query string into AST
  // 2. Resolve default fields (df) on bare values and phrases
  // 3. On parse failure: return { ast: null, errors: [...] }
  throw new Error('Not implemented');
}
