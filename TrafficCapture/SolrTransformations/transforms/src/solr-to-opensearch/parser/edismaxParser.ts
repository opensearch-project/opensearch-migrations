/**
 * eDisMax parser for Solr Extended DisMax query syntax.
 *
 * eDisMax extends Lucene syntax by distributing unfielded search terms across
 * multiple fields specified in the `qf` (query fields) parameter, each with
 * optional boost weights. This is how Solr's eDisMax achieves "search across
 * all fields" behavior.
 *
 * Algorithm:
 *   1. Parse the qf string into WeightedField[] (e.g., "title^2 content" →
 *      [{field:"title", boost:2}, {field:"content", boost:1}])
 *   2. Parse the query using the Lucene parser with a placeholder default field
 *   3. Walk the AST and replace every unfielded term (FieldNode where
 *      field === placeholderDf) with a BoolNode(should) containing one
 *      FieldNode per qf field, each wrapped in BoostNode if boost ≠ 1.0
 *   4. Explicitly fielded terms (e.g., "title:java") are left unchanged
 *   5. If pf (phrase fields) is specified, append PhraseNode entries for
 *      phrase boosting across those fields
 *
 * Example: query "java" with qf="title^2 content" produces:
 *   BoolNode(should: [
 *     BoostNode(FieldNode(title, java), 2),
 *     FieldNode(content, java)
 *   ])
 *
 * Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7
 */

import type {
  ASTNode,
  BoolNode,
  BoostNode,
  FieldNode,
  PhraseNode,
} from '../ast/nodes';
import type { Token } from '../lexer/lexer';
import { parseLucene, type ParseResult } from './luceneParser';

export interface EDisMaxConfig {
  qf: string;   // e.g. "title^2 content"
  pf?: string;  // e.g. "title^3"
  df?: string;
}

export interface WeightedField {
  field: string;
  boost: number;  // default 1.0
}

/**
 * Parse a qf/pf field list string into WeightedField[].
 * Format: "field1^boost1 field2 field3^boost3"
 * Fields without explicit boost get 1.0.
 */
export function parseFieldList(fieldList: string): WeightedField[] {
  const result: WeightedField[] = [];
  const parts = fieldList.trim().split(/\s+/);

  for (const part of parts) {
    if (!part) continue;
    const caretIdx = part.indexOf('^');
    if (caretIdx >= 0) {
      const field = part.substring(0, caretIdx);
      const boost = parseFloat(part.substring(caretIdx + 1));
      result.push({ field, boost: isNaN(boost) ? 1.0 : boost });
    } else {
      result.push({ field: part, boost: 1.0 });
    }
  }

  return result;
}

/**
 * Walk an AST and distribute unfielded terms across qf fields.
 * An "unfielded term" is a FieldNode where field === placeholderDf.
 */
function distributeQfFields(node: ASTNode, qfFields: WeightedField[], placeholderDf: string): ASTNode {
  switch (node.type) {
    case 'field': {
      if (node.field === placeholderDf) {
        return expandToQfFields(node.value, qfFields);
      }
      return node;
    }

    case 'phrase': {
      if (node.field === placeholderDf) {
        return expandPhraseToQfFields(node.text, qfFields);
      }
      return node;
    }

    case 'bool': {
      return {
        ...node,
        must: node.must.map((c) => distributeQfFields(c, qfFields, placeholderDf)),
        should: node.should.map((c) => distributeQfFields(c, qfFields, placeholderDf)),
        must_not: node.must_not.map((c) => distributeQfFields(c, qfFields, placeholderDf)),
      };
    }

    case 'boost': {
      return {
        ...node,
        child: distributeQfFields(node.child, qfFields, placeholderDf),
      };
    }

    case 'group': {
      return {
        ...node,
        child: distributeQfFields(node.child, qfFields, placeholderDf),
      };
    }

    case 'range': {
      if (node.field === placeholderDf) {
        // Range on default field — can't easily distribute, leave as-is
        // with the first qf field as a reasonable default
        return qfFields.length > 0
          ? { ...node, field: qfFields[0].field }
          : node;
      }
      return node;
    }

    case 'matchAll':
      return node;
  }
}

/**
 * Expand a single unfielded term into a BoolNode(should) with one
 * FieldNode per qf field, each wrapped in BoostNode if boost ≠ 1.0.
 */
function expandToQfFields(value: string, qfFields: WeightedField[]): ASTNode {
  if (qfFields.length === 1) {
    const wf = qfFields[0];
    const fieldNode: FieldNode = { type: 'field', field: wf.field, value };
    if (wf.boost !== 1.0) {
      return { type: 'boost', child: fieldNode, value: wf.boost } as BoostNode;
    }
    return fieldNode;
  }

  const shouldClauses: ASTNode[] = qfFields.map((wf) => {
    const fieldNode: FieldNode = { type: 'field', field: wf.field, value };
    if (wf.boost !== 1.0) {
      return { type: 'boost', child: fieldNode, value: wf.boost } as BoostNode;
    }
    return fieldNode;
  });

  const boolNode: BoolNode = {
    type: 'bool',
    must: [],
    should: shouldClauses,
    must_not: [],
  };
  return boolNode;
}

/**
 * Expand an unfielded phrase into a BoolNode(should) with one
 * PhraseNode per qf field, each wrapped in BoostNode if boost ≠ 1.0.
 */
function expandPhraseToQfFields(text: string, qfFields: WeightedField[]): ASTNode {
  if (qfFields.length === 1) {
    const wf = qfFields[0];
    const phraseNode: PhraseNode = { type: 'phrase', text, field: wf.field };
    if (wf.boost !== 1.0) {
      return { type: 'boost', child: phraseNode, value: wf.boost } as BoostNode;
    }
    return phraseNode;
  }

  const shouldClauses: ASTNode[] = qfFields.map((wf) => {
    const phraseNode: PhraseNode = { type: 'phrase', text, field: wf.field };
    if (wf.boost !== 1.0) {
      return { type: 'boost', child: phraseNode, value: wf.boost } as BoostNode;
    }
    return phraseNode;
  });

  const boolNode: BoolNode = {
    type: 'bool',
    must: [],
    should: shouldClauses,
    must_not: [],
  };
  return boolNode;
}

/**
 * Build phrase boost nodes from pf fields.
 * Collects all unfielded term values from the original AST and creates
 * PhraseNode entries for each pf field.
 */
function buildPhraseBoostNodes(originalAst: ASTNode, pfFields: WeightedField[], placeholderDf: string): ASTNode[] {
  const terms = collectUnfieldedValues(originalAst, placeholderDf);
  if (terms.length < 2) return []; // Need at least 2 terms for a phrase

  const phraseText = terms.join(' ');
  const nodes: ASTNode[] = [];

  for (const wf of pfFields) {
    const phraseNode: PhraseNode = { type: 'phrase', text: phraseText, field: wf.field };
    if (wf.boost !== 1.0) {
      nodes.push({ type: 'boost', child: phraseNode, value: wf.boost } as BoostNode);
    } else {
      nodes.push(phraseNode);
    }
  }

  return nodes;
}

/** Collect all unfielded term values from the AST (before qf distribution). */
function collectUnfieldedValues(node: ASTNode, placeholderDf: string): string[] {
  switch (node.type) {
    case 'field':
      return node.field === placeholderDf ? [node.value] : [];
    case 'bool':
      return [
        ...node.must.flatMap((c) => collectUnfieldedValues(c, placeholderDf)),
        ...node.should.flatMap((c) => collectUnfieldedValues(c, placeholderDf)),
        ...node.must_not.flatMap((c) => collectUnfieldedValues(c, placeholderDf)),
      ];
    case 'boost':
      return collectUnfieldedValues(node.child, placeholderDf);
    case 'group':
      return collectUnfieldedValues(node.child, placeholderDf);
    default:
      return [];
  }
}

/**
 * Parse an eDisMax query.
 *
 * Algorithm:
 * 1. Parse qf string into WeightedField[]
 * 2. Parse token stream using Lucene parser with placeholder default field
 * 3. Walk AST to distribute unfielded terms across qf fields
 * 4. If pf specified, append PhraseNode entries for phrase boosting
 */
export function parseEdismax(tokens: Token[], config: EDisMaxConfig): ParseResult {
  const placeholderDf = config.df || '_text_';
  const qfFields = parseFieldList(config.qf);

  // Step 2: Parse with Lucene parser using placeholder df
  const luceneResult = parseLucene(tokens, placeholderDf);

  if (luceneResult.errors.length > 0) {
    return luceneResult;
  }

  // If no qf fields, just return the Lucene result as-is
  if (qfFields.length === 0) {
    return luceneResult;
  }

  // Step 3: Walk AST and distribute unfielded terms across qf fields
  // Save original AST before distribution for phrase boost collection
  const originalAst = luceneResult.ast;
  let distributedAst = distributeQfFields(originalAst, qfFields, placeholderDf);

  // Step 4: If pf specified, append phrase boost nodes
  if (config.pf) {
    const pfFields = parseFieldList(config.pf);
    const phraseBoostNodes = buildPhraseBoostNodes(originalAst, pfFields, placeholderDf);

    if (phraseBoostNodes.length > 0) {
      // Wrap existing AST + phrase boosts in a should BoolNode
      if (distributedAst.type === 'bool') {
        distributedAst = {
          ...distributedAst,
          should: [...distributedAst.should, ...phraseBoostNodes],
        };
      } else {
        distributedAst = {
          type: 'bool',
          must: [],
          should: [distributedAst, ...phraseBoostNodes],
          must_not: [],
        };
      }
    }
  }

  return { ast: distributedAst, errors: [] };
}
