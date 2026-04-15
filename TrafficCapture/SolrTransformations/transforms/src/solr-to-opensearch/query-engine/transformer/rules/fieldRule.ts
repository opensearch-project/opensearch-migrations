/**
 * Transformation rule for FieldNode → OpenSearch query.
 *
 * Maps Solr's field:value syntax to the appropriate OpenSearch query type:
 *   - field:* (existence) → exists query
 *   - field:roam~ / field:roam~2 (fuzzy) → fuzzy query
 *   - field:te?t / field:test* (wildcard) → wildcard query
 *   - field:value (plain) → match query
 *
 * We use `match` for plain values because Solr's standard query parser
 * analyzes field values at query time, matching OpenSearch's match behavior.
 *
 * All output uses the expanded form with nested field object to support
 * boost injection by boostRule:
 *   {"queryType": {"field": {"param": "value"}}}
 *
 * Examples:
 *   `title:java`   → Map{"match" → Map{"title" → Map{"query" → "java"}}}
 *   `title:*`      → Map{"exists" → Map{"field" → "title"}}
 *   `title:te?t`   → Map{"wildcard" → Map{"title" → Map{"value" → "te?t"}}}
 *   `title:test*`  → Map{"wildcard" → Map{"title" → Map{"value" → "test*"}}}
 *   `title:roam~`  → Map{"fuzzy" → Map{"title" → Map{"value" → "roam"}}}
 *   `title:roam~2` → Map{"fuzzy" → Map{"title" → Map{"value" → "roam", "fuzziness" → 2}}}
 */

import type { ASTNode } from '../../ast/nodes';
import type { TransformRuleFn } from '../types';

/** Detects wildcard patterns: contains * or ? (but not sole *) */
const WILDCARD_PATTERN = /[*?]/;

/** Detects fuzzy search: term~ or term~N at end of value */
const FUZZY_PATTERN = /^(.+?)~(\d?)$/;

export const fieldRule: TransformRuleFn = (
  node: ASTNode,
  _transformChild,
): Map<string, any> => {
  const { field, value } = node;

  // Existence search: field:* → exists query
  if (value === '*') {
    return new Map([['exists', new Map([['field', field]])]]);
  }

  // Fuzzy search: field:roam~ or field:roam~2 → fuzzy query
  // Check fuzzy before wildcard since ~ is more specific
  const fuzzyMatch = FUZZY_PATTERN.exec(value);
  if (fuzzyMatch) {
    const term = fuzzyMatch[1];
    const distance = fuzzyMatch[2];
    const fuzzyParams = new Map<string, any>([['value', term]]);
    if (distance) {
      fuzzyParams.set('fuzziness', parseInt(distance, 10));
    }
    return new Map([['fuzzy', new Map([[field, fuzzyParams]])]]);
  }

  // Wildcard search: field:te?t or field:test* → wildcard query
  if (WILDCARD_PATTERN.test(value)) {
    return new Map([['wildcard', new Map([[field, new Map([['value', value]])]])]]);
  }

  // Plain value: field:value → match query (expanded form for boost compatibility)
  return new Map([['match', new Map([[field, new Map([['query', value]])]])]]);
};