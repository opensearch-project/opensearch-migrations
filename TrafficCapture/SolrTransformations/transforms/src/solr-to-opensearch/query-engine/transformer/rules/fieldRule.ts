/**
 * Transformation rule for FieldNode → OpenSearch query.
 *
 * Maps Solr's field:value syntax to the appropriate OpenSearch query type:
 *   - field:* (existence) → exists query
 *   - field:roam~ / field:roam~2 (fuzzy) → fuzzy query
 *   - field:te?t / field:test* (wildcard) → wildcard query
 *   - field:value (plain, text field) → match query
 *   - field:value (plain, any other type or unknown) → term query
 *
 * For plain values, the query type depends on the field's OpenSearch type from
 * the schema (passed via fieldTypes):
 *   - `text` → match query (analyzed, full-text search)
 *   - anything else (keyword, integer, date, boolean, etc.) → term query (exact match)
 *   - unknown (field not in fieldTypes) → match query (safe default — avoids breaking
 *     text fields that weren't listed in the schema)
 
 *
 * All output uses the expanded form with nested field object to support
 * boost injection by boostRule:
 *   {"queryType": {"field": {"param": "value"}}}
 *
 * Examples (text field or unknown):
 *   `title:java`   → Map{"match" → Map{"title" → Map{"query" → "java"}}}
 *   `title:*`      → Map{"exists" → Map{"field" → "title"}}
 *   `title:te?t`   → Map{"wildcard" → Map{"title" → Map{"value" → "te?t"}}}
 *   `title:test*`  → Map{"wildcard" → Map{"title" → Map{"value" → "test*"}}}
 *   `title:roam~`  → Map{"fuzzy" → Map{"title" → Map{"value" → "roam"}}}
 *   `title:roam~2` → Map{"fuzzy" → Map{"title" → Map{"value" → "roam", "fuzziness" → 2}}}
 *
 * Examples (with schema, non-text field):
 *   `status:active` (status=keyword) → Map{"term" → Map{"status" → "active"}}
 *   `id:42`         (id=keyword)     → Map{"term" → Map{"id" → "42"}}
 */

import type { ASTNode, FieldNode } from '../../ast/nodes';
import type { TransformRuleFn } from '../types';

/** Detects wildcard patterns: contains * or ? (but not sole *) */
const WILDCARD_PATTERN = /[*?]/;

/** Detects fuzzy search: term~ or term~N at end of value */
const FUZZY_PATTERN = /^(.+?)~(\d?)$/;

/** Shared empty map used as the default when no fieldTypes are provided. */
const EMPTY_FIELD_TYPES: ReadonlyMap<string, string> = new Map();

/**
 * Classify a Solr field as analyzed text or exact based on its fieldType Java class.
 *
 * A field is analyzed (text) if its fieldType class contains "TextField"
 * (e.g. solr.TextField, org.apache.solr.schema.TextField, custom subclasses).
 * Everything else is exact — regardless of the type name.
 *
 * Using the class rather than the type name avoids false positives from
 * misleadingly-named types (e.g. a type named "text_acs" backed by IntPointField
 * is exact, not analyzed).
 */
function isTextField(fieldTypeClass: string): boolean {
  return fieldTypeClass.includes('TextField');
}

/**
 * TransformRuleFn for FieldNode. Uses the optional `fieldTypes` parameter
 * (field name → Solr fieldType class) to choose term vs match for plain values.
 * Falls back to match when fieldTypes is absent or the field is not listed.
 */
export const fieldRule: TransformRuleFn = (
  node: ASTNode,
  _transformChild,
  fieldTypes = EMPTY_FIELD_TYPES,
): Map<string, any> => {
  const { field, value } = node as FieldNode;

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

  // Plain value — choose query type based on field's Solr fieldType class.
  //
  // class.includes("TextField") → analyzed → match query.
  // Everything else (solr.StrField, solr.IntPointField, etc.) → exact → term query.
  // Unknown fields (not in fieldTypes) fall back to match. Neither match nor term
  // is universally correct without schema information — match applies whatever
  // search_analyzer is configured (which may differ from index-time analysis),
  // while term bypasses analysis entirely. For unknown fields we choose match as
  // the best-effort default since it matches Solr's query-time analysis behavior
  // for text fields, which are the most common case when schema metadata is absent.
  const fieldTypeClass = fieldTypes.get(field);
  if (fieldTypeClass !== undefined && !isTextField(fieldTypeClass)) {
    // Known non-text field: term query — exact match, no analysis
    return new Map([['term', new Map([[field, new Map([['value', value]])]])]]);
  }

  // text field or unknown type: match query (expanded form for boost compatibility).
  return new Map([['match', new Map([[field, new Map([['query', value]])]])]]);
};