/**
 * Transformation rule for BareNode → OpenSearch `multi_match` or `query_string` query.
 *
 * When queryFields is set (edismax/dismax with qf param), emits a multi_match
 * query across those fields. Each field spec is passed through as-is from the
 * qf param ("field" or "field^boost") since OpenSearch uses the same format.
 *
 * type "best_fields" matches Solr's dismax behavior for terms (score = max
 * field score). For phrases (isPhrase=true), uses type "phrase".
 *
 * Falls back to query_string when queryFields is absent (standard parser).
 *
 * Examples (multi_match):
 *   `java` with qf="title^2 body"
 *     → Map{"multi_match" → Map{"query"→"java", "fields"→["title^2","body"], "type"→"best_fields"}}
 *   `"hello world"` with qf="title body"
 *     → Map{"multi_match" → Map{"query"→"hello world", "fields"→["title","body"], "type"→"phrase"}}
 *
 * Examples (query_string fallback):
 *   `java` (no df) → Map{"query_string" → Map{"query" → "java"}}
 *   `java` (df="content") → Map{"query_string" → Map{"query" → "java", "default_field" → "content"}}
 *   `"hello world"` (no df) → Map{"query_string" → Map{"query" → "\"hello world\""}}
 *   `"hello world"` (df="title") → Map{"query_string" → Map{"query" → "\"hello world\"", "default_field" → "title"}}
 *   `foo:bar` (no df) → Map{"query_string" → Map{"query" → "foo\\:bar"}}
 *
 * Note: Boosts are handled separately by BoostNode.
 */

import type { ASTNode } from '../../ast/nodes';
import type { TransformRuleFn } from '../types';

/**
 * Characters that have special meaning in OpenSearch query_string syntax.
 * These must be escaped with a backslash to be treated as literals.
 * Reserved: + - = && || > < ! ( ) { } [ ] ^ " ~ * ? : \ /
 * @see https://docs.opensearch.org/latest/query-dsl/full-text/query-string/#reserved-characters
 */
const QUERY_STRING_SPECIAL_CHARS = /[+\-=&|><!(){}[\]^"~*?:\\/]/g;

/**
 * Escapes special characters in a query string to prevent them from being
 * interpreted as query_string operators.
 */
function escapeQueryString(text: string): string {
  // NOSONAR: Using replace() with global regex is equivalent to replaceAll() and avoids ES2021 lib dependency
  return text.replace(QUERY_STRING_SPECIAL_CHARS, String.raw`\$&`); // NOSONAR
}

export const bareRule: TransformRuleFn = (
  node: ASTNode,
  // Bare is a leaf node — transformChild not used
  _transformChild,
): Map<string, any> => {
  const { value, isPhrase, defaultField, queryFields, tieBreaker } = node;

  // multi_match path: qf fields provided (edismax/dismax)
  if (queryFields && queryFields.length > 0) {
    const entries: [string, any][] = [
      ['query', value],
      ['fields', queryFields],
      ['type', isPhrase ? 'phrase' : 'best_fields'],
    ];
    if (tieBreaker !== undefined) entries.push(['tie_breaker', tieBreaker]);
    return new Map([['multi_match', new Map<string, any>(entries)]]);
  }

  // query_string fallback (standard parser / df only)
  const escapedQuery = escapeQueryString(value);

  // Wrap phrases in quotes for query_string to treat as phrase search
  const queryText = isPhrase ? `"${escapedQuery}"` : escapedQuery;

  // Include default_field only when explicitly set
  // TODO: Evaluate match query vs query string based on validation dataset
  if (defaultField) {
    return new Map([['query_string', new Map([['query', queryText], ['default_field', defaultField]])]]);
  }

  return new Map([['query_string', new Map([['query', queryText]])]]);
};
