/**
 * Transformation rule for FieldNode → OpenSearch `match` or `exists` query.
 *
 * Maps Solr's field:value syntax to OpenSearch's match query.
 * Special case: field:* (existence search) maps to exists query.
 *
 * We use `match` instead of `term` because Solr's standard query parser
 * analyzes field values at query time, matching OpenSearch's match behavior.
 * Using `term` would require exact matches against the indexed tokens,
 * which could fail for analyzed fields (e.g., case differences).
 *
 * Output uses the expanded match query form with nested field object:
 *   `{"match": {"field": {"query": "value"}}}`
 * This allows boostRule to add boost inside the field object:
 *   `{"match": {"field": {"query": "value", "boost": 2}}}`
 *
 * Examples:
 *   `title:java` → Map{"match" → Map{"title" → Map{"query" → "java"}}}
 *   `title:*` → Map{"exists" → Map{"field" → "title"}}
 *
 * Unsupported (throws error):
 *   - Wildcards (te?t, tes*) - throws error
 *   - Fuzzy searches (roam~, roam~1) - throws error
 *
 * Note: Boosts are handled separately by BoostNode.
 */

import type { ASTNode } from '../../ast/nodes';
import type { TransformRuleFn } from '../types';

/** Regex to detect wildcard patterns (contains * or ?) */
const WILDCARD_PATTERN = /[*?]/;

/** Regex to detect fuzzy search patterns (term~ or term~N at end) */
const FUZZY_PATTERN = /~\d?$/;

export const fieldRule: TransformRuleFn = (
  node: ASTNode,
  // Field is a leaf node — transformChild not used
  _transformChild,
): Map<string, any> => {
  const { field, value } = node;

  // Existence search (field:*) → exists query
  // TODO: Add support for fuzzy queries
  if (value === '*') {
    return new Map([['exists', new Map([['field', field]])]]);
  }

  // Detect unsupported fuzzy patterns (check before wildcard since ~ is more specific)
  // TODO: Add support for wildcard queries
  if (FUZZY_PATTERN.test(value)) {
    const msg = `[fieldRule] Fuzzy queries aren't supported yet. Query: ${field}:${value}`;
    console.error(msg);
    throw new Error(msg);
  }

  // Detect unsupported wildcard patterns
  // TODO: Add support for wildcard queries
  if (WILDCARD_PATTERN.test(value)) {
    const msg = `[fieldRule] Wildcard queries aren't supported yet. Query: ${field}:${value}`;
    console.error(msg);
    throw new Error(msg);
  }

  // Use expanded form: {"match": {"field": {"query": "value"}}}
  // This allows boostRule to add boost inside the field object
  return new Map([['match', new Map([[field, new Map([['query', value]])]])]]);
};
