/**
 * Transformation rule for FieldNode → OpenSearch query.
 *
 * Maps Solr's field:value syntax to the appropriate OpenSearch query type:
 *   - field:* (existence) → exists query
 *   - field:roam~ / field:roam~2 (fuzzy) → fuzzy query
 *   - field:te?t / field:test* (wildcard) → wildcard query
 *   - field:value (keyword field) → term query (exact match)
 *   - field:value (text field or unknown) → match query (analyzed)
 *
 * We use `match` for text fields because Solr's standard query parser
 * analyzes field values at query time, matching OpenSearch's match behavior.
 * For keyword/numeric/date fields, `term` is used for exact matching.
 *
 * When fieldMappings are provided, the rule uses the field's OpenSearch type
 * to choose between term (keyword/numeric/date) and match (text) queries.
 * Without fieldMappings, defaults to match query for backward compatibility.
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
 *
 * Note: Boosts are handled separately by BoostNode.
 */

import type { ASTNode } from '../../ast/nodes';
import type { TransformRuleFn, FieldMappings } from '../types';

/** Regex to detect wildcard patterns (contains * or ?) */
const WILDCARD_PATTERN = /[*?]/;

/** Regex to detect fuzzy search patterns (term~ or term~N at end) */
const FUZZY_PATTERN = /^(.+?)~(\d?)$/;

/** OpenSearch field types that should use term query (exact match, not analyzed) */
const KEYWORD_TYPES = new Set(['keyword', 'integer', 'long', 'float', 'double', 'boolean', 'date', 'ip']);

export const fieldRule: TransformRuleFn = (
  node: ASTNode,
  // Field is a leaf node — transformChild not used
  _transformChild,
  fieldMappings?: FieldMappings,
): Map<string, any> => {
  const { field, value } = node;

  // Existence search (field:*) → exists query
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
    if (distance !== '') {
      const fuzziness = Number.parseInt(distance, 10);
      // Both Solr and OpenSearch only support fuzziness 0, 1, or 2.
      // Clamp to 2 to match Solr's behavior (Solr silently caps at 2).
      fuzzyParams.set('fuzziness', Math.min(fuzziness, 2));
    }
    return new Map([['fuzzy', new Map([[field, fuzzyParams]])]]);
  }

  // Wildcard search: field:te?t or field:test* → wildcard query
  if (WILDCARD_PATTERN.test(value)) {
    return new Map([['wildcard', new Map([[field, new Map([['value', value]])]])]]);
  }

  // Choose query type based on field metadata
  const fieldType = fieldMappings?.get(field);
  if (fieldType && KEYWORD_TYPES.has(fieldType)) {
    // Keyword/numeric/date fields → term query (exact match, no analysis)
    return new Map([['term', new Map([[field, new Map([['value', value]])]])]]);
  }

  // Default: text fields or unknown → match query (analyzed)
  // Use expanded form: {"match": {"field": {"query": "value"}}}
  // This allows boostRule to add boost inside the field object
  return new Map([['match', new Map([[field, new Map([['query', value]])]])]]);
};
