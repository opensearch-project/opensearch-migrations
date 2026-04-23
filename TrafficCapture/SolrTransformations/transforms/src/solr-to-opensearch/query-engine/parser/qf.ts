/**
 * Query Fields (qf/pf) parameter parsing and AST annotation.
 *
 * The `qf` and `pf` parameters are used by eDisMax and DisMax query parsers
 * to specify which fields to search and their relative boost weights.
 *
 * Format: `qf=title^2 body description^0.5`
 *
 * OpenSearch's multi_match `fields` array uses the exact same format
 * ("field" or "field^boost"), so tokens are passed through as-is with
 * no parsing of the boost value needed.
 *
 * This module has two responsibilities:
 *   1. parseQueryFields — split the raw param string into field spec tokens
 *   2. applyQueryFields — walk the AST and stamp queryFields onto BareNodes
 */

import type { ASTNode } from '../ast/nodes';

/**
 * Parse a `qf` or `pf` parameter value into an array of field spec strings.
 *
 * Each whitespace-separated token is passed through as-is — either "fieldName"
 * or "fieldName^boost" — matching OpenSearch multi_match fields format directly.
 *
 * Returns undefined when the param is absent or empty.
 *
 * Examples:
 *   "title^2 body"     → ["title^2", "body"]
 *   "title^2 body^0.5" → ["title^2", "body^0.5"]
 *   "title"            → ["title"]
 *   "" / undefined     → undefined
 */
export function parseQueryFields(qf: string | undefined): string[] | undefined {
  if (!qf?.trim()) return undefined;
  const fields = qf.trim().split(/\s+/);
  return fields.length > 0 ? fields : undefined;
}

/**
 * Walk the AST and stamp queryFields onto every BareNode.
 *
 * Called as a post-parse pass when defType is edismax or dismax and qf is set.
 * BareNodes represent unfielded terms/phrases — in edismax/dismax these are
 * expanded across all qf fields by the transformer (via multi_match).
 *
 * Only BareNodes are annotated — FieldNodes, PhraseNodes, and RangeNodes
 * already have explicit fields from the query syntax and are left unchanged.
 */
export function applyQueryFields(node: ASTNode, queryFields: string[]): void {
  switch (node.type) {
    case 'bare':
      node.queryFields = queryFields;
      // Clear defaultField — queryFields takes precedence and bareRule uses queryFields first
      delete node.defaultField;
      break;
    case 'bool':
      node.and.forEach((child) => applyQueryFields(child, queryFields));
      node.or.forEach((child) => applyQueryFields(child, queryFields));
      node.not.forEach((child) => applyQueryFields(child, queryFields));
      break;
    case 'boost':
    case 'group':
    case 'filter':
      applyQueryFields(node.child, queryFields);
      break;
    case 'localParams':
      if (node.body) applyQueryFields(node.body, queryFields);
      break;
    case 'field':
    case 'phrase':
    case 'range':
    case 'matchAll':
      break;
    /* v8 ignore next 3 */
    default: {
      const _exhaustive: never = node;
      throw new Error(`Unhandled node type: ${(_exhaustive as ASTNode).type}`);
    }
  }
}
