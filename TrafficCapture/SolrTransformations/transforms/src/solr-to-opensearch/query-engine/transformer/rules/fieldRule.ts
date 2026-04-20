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
 * When fieldMappings are provided, the rule uses the field's OpenSearch type
 * to choose between term (keyword/numeric/date) and match (text) queries.
 * Without fieldMappings, defaults to match query for backward compatibility.
 */

import type { ASTNode } from '../../ast/nodes';
import type { TransformRuleFn, FieldMappings } from '../types';

/** Detects wildcard patterns: contains * or ? (but not sole *) */
const WILDCARD_PATTERN = /[*?]/;

/** Detects fuzzy search: term~ or term~N at end of value */
const FUZZY_PATTERN = /^(.+?)~(\d?)$/;

/** OpenSearch field types that should use term query (exact match, not analyzed) */
const KEYWORD_TYPES = new Set(['keyword', 'integer', 'long', 'float', 'double', 'boolean', 'date', 'ip']);

export const fieldRule: TransformRuleFn = (
  node: ASTNode,
  _transformChild,
  fieldMappings?: FieldMappings,
): Map<string, any> => {
  const { field, value } = node;

  // Existence search: field:* → exists query
  if (value === '*') {
    return new Map([['exists', new Map([['field', field]])]]);
  }

  // Fuzzy search: field:roam~ or field:roam~2 → fuzzy query
  const fuzzyMatch = FUZZY_PATTERN.exec(value);
  if (fuzzyMatch) {
    const term = fuzzyMatch[1];
    const distance = fuzzyMatch[2];
    const fuzzyParams = new Map<string, any>([['value', term]]);
    if (distance !== '') {
      const fuzziness = Number.parseInt(distance, 10);
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
    return new Map([['term', new Map([[field, new Map([['value', value]])]])]]);
  }

  // Default: text fields or unknown → match query (analyzed)
  return new Map([['match', new Map([[field, new Map([['query', value]])]])]]);
};
