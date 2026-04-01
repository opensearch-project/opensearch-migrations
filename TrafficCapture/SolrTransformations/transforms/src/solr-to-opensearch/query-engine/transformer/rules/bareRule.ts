/**
 * Transformation rule for BareNode → OpenSearch `query_string` query.
 *
 * Maps bare Solr terms/phrases (without field prefix) to OpenSearch's query_string.
 * When defaultField is set, includes it in the output; otherwise omits it to let
 * OpenSearch use its default behavior.
 *
 * For phrases (isPhrase=true), wraps the query in quotes so OpenSearch's
 * query_string treats it as a phrase search matching words in order.
 *
 * Special characters are escaped to prevent query_string from interpreting them
 * as operators (e.g., foo:bar would be treated as a field query without escaping).
 *
 * Examples:
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
  const { value, isPhrase, defaultField } = node;

  // Escape special characters to prevent query_string from interpreting them as operators
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
