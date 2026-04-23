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
export function parseFieldSpecs(fieldSpec: string | undefined): string[] | undefined {
  if (!fieldSpec?.trim()) return undefined;
  const fields = fieldSpec.trim().split(/\s+/);
  return fields.length > 0 ? fields : undefined;
}

/**
 * Walk the AST and stamp queryFields (and optionally tieBreaker) onto every BareNode.
 *
 * Called as a post-parse pass when defType is edismax or dismax and qf is set.
 * BareNodes represent unfielded terms/phrases — in edismax/dismax these are
 * expanded across all qf fields by the transformer (via multi_match).
 *
 * Only BareNodes are annotated — FieldNodes, PhraseNodes, and RangeNodes
 * already have explicit fields from the query syntax and are left unchanged.
 *
 * @param node - The AST node to annotate.
 * @param params - All request params. Reads `qf` and `tie`.
 */
export function applyQueryFields(node: ASTNode, params: ReadonlyMap<string, string>): void {
  const queryFields = parseFieldSpecs(params.get('qf'));
  if (!queryFields) return;

  const tieRaw = params.get('tie');
  const tieBreaker = tieRaw !== undefined ? Number.parseFloat(tieRaw) : undefined;
  const validTie = tieBreaker !== undefined && !Number.isNaN(tieBreaker) ? tieBreaker : undefined;

  _applyQueryFields(node, queryFields, validTie);
}

function _applyQueryFields(node: ASTNode, queryFields: string[], tieBreaker: number | undefined): void {
  switch (node.type) {
    case 'bare':
      node.queryFields = queryFields;
      if (tieBreaker !== undefined) node.tieBreaker = tieBreaker;
      // Clear defaultField — queryFields takes precedence and bareRule uses queryFields first
      delete node.defaultField;
      break;
    case 'bool':
      node.and.forEach((child) => _applyQueryFields(child, queryFields, tieBreaker));
      node.or.forEach((child) => _applyQueryFields(child, queryFields, tieBreaker));
      node.not.forEach((child) => _applyQueryFields(child, queryFields, tieBreaker));
      break;
    case 'boost':
    case 'group':
    case 'filter':
      _applyQueryFields(node.child, queryFields, tieBreaker);
      break;
    case 'localParams':
      if (node.body) _applyQueryFields(node.body, queryFields, tieBreaker);
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
