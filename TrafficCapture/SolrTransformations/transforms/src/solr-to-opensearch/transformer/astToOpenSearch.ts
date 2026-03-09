/**
 * AST-to-OpenSearch transformer.
 *
 * Visitor-based switch on node.type that converts AST nodes into
 * OpenSearch Query DSL as nested Maps. ALL output uses `new Map()` —
 * never plain objects — for GraalVM JavaMap interop.
 *
 * Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 5.7, 5.8, 5.9
 */

import type { ASTNode } from '../ast/nodes';

/** Transform an AST node into an OpenSearch DSL Map. */
export function transformNode(node: ASTNode, df: string): Map<string, any> {
  switch (node.type) {
    case 'matchAll':
      return new Map([['match_all', new Map()]]);

    case 'field':
      return new Map([['term', new Map([[node.field, node.value]])]]);

    case 'phrase': {
      const field = node.field ?? df;
      return new Map([['match_phrase', new Map([[field, node.text]])]]);
    }

    case 'range': {
      const bounds = new Map<string, string>();
      bounds.set(node.lowerInclusive ? 'gte' : 'gt', node.lower);
      bounds.set(node.upperInclusive ? 'lte' : 'lt', node.upper);
      return new Map([['range', new Map([[node.field, bounds]])]]);
    }

    case 'bool': {
      const must = node.must.map((c) => transformNode(c, df));
      const should = node.should.map((c) => transformNode(c, df));
      const mustNot = node.must_not.map((c) => transformNode(c, df));

      // Bool unwrapping (Req 5.9): if exactly one clause in one array
      // and the other two are empty, return the child directly.
      const totalClauses = must.length + should.length + mustNot.length;
      if (totalClauses === 1) {
        if (must.length === 1) return must[0];
        if (should.length === 1) return should[0];
        if (mustNot.length === 1) {
          // Single must_not still needs bool wrapper — can't unwrap negation
          // because there's no standalone "not" in OpenSearch DSL.
        }
      }

      const boolMap = new Map<string, any>();
      if (must.length > 0) boolMap.set('must', must);
      if (should.length > 0) boolMap.set('should', should);
      if (mustNot.length > 0) boolMap.set('must_not', mustNot);

      return new Map([['bool', boolMap]]);
    }

    case 'boost': {
      const inner = transformNode(node.child, df);
      // Add "boost" key to the inner query Map.
      // The inner map is something like Map{"term" → Map{...}}.
      // We need to add boost inside the inner value map.
      addBoostToMap(inner, node.value);
      return inner;
    }

    case 'group':
      // Transparent — just recurse into child
      return transformNode(node.child, df);
  }
}

/**
 * Add a "boost" entry to the innermost value Map of a query DSL Map.
 *
 * For example, Map{"term" → Map{"title" → "java"}} becomes
 * Map{"term" → Map{"title" → "java", "boost" → 2}}.
 *
 * For bool queries, boost is added directly to the bool Map.
 */
function addBoostToMap(queryMap: Map<string, any>, boostValue: number): void {
  // Get the first (and typically only) entry
  const firstKey = queryMap.keys().next().value;
  if (firstKey === undefined) return;

  const innerValue = queryMap.get(firstKey);

  if (firstKey === 'bool') {
    // For bool queries, add boost to the bool map itself
    (innerValue as Map<string, any>).set('boost', boostValue);
  } else if (firstKey === 'match_all') {
    // For match_all, add boost to the inner map
    (innerValue as Map<string, any>).set('boost', boostValue);
  } else if (innerValue instanceof Map) {
    // For term, match_phrase, range — add boost to the inner map
    innerValue.set('boost', boostValue);
  }
}
