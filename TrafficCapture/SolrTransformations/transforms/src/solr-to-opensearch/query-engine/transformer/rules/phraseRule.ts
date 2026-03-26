/**
 * Transformation rule for PhraseNode → OpenSearch `match_phrase` query.
 *
 * Maps Solr's explicit field:"phrase" syntax to OpenSearch's match_phrase query.
 * Bare phrases (without field prefix) are handled by bareRule instead.
 *
 * Examples:
 *   `title:"hello world"` → Map{"match_phrase" → Map{"title" → Map{"query" → "hello world"}}}
 *   `description:"search engine"` → Map{"match_phrase" → Map{"description" → Map{"query" → "search engine"}}}
 *
 * Note: Boosts are handled separately by BoostNode.
 */

import type { ASTNode } from '../../ast/nodes';
import type { TransformRuleFn } from '../types';

export const phraseRule: TransformRuleFn = (
  node: ASTNode,
  // Phrase is a leaf node — transformChild not used
  _transformChild,
): Map<string, any> => {
  const { field, text } = node;

  // Explicit field → use match_phrase query
  return new Map([['match_phrase', new Map([[field, new Map([['query', text]])]])]]);
};
