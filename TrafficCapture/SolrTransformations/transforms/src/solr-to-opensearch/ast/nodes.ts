/**
 * AST node types for the Solr query parser.
 *
 * Uses discriminated unions — each node carries a `type` string literal
 * discriminant for exhaustive switch-based dispatch in the transformer.
 */

export interface BoolNode {
  type: 'bool';
  must: ASTNode[];
  should: ASTNode[];
  must_not: ASTNode[];
}

export interface FieldNode {
  type: 'field';
  field: string;
  value: string;
}

export interface PhraseNode {
  type: 'phrase';
  text: string;
  field?: string;
}

export interface RangeNode {
  type: 'range';
  field: string;
  lower: string;
  upper: string;
  lowerInclusive: boolean;
  upperInclusive: boolean;
}

export interface MatchAllNode {
  type: 'matchAll';
}

export interface GroupNode {
  type: 'group';
  child: ASTNode;
}

export interface BoostNode {
  type: 'boost';
  child: ASTNode;
  value: number;
}

export type ASTNode =
  | BoolNode
  | FieldNode
  | PhraseNode
  | RangeNode
  | MatchAllNode
  | GroupNode
  | BoostNode;


/** Convert an AST back to canonical Solr query syntax for round-trip testing. */
export function prettyPrint(node: ASTNode): string {
  switch (node.type) {
    case 'matchAll':
      return '*:*';

    case 'field':
      return `${node.field}:${node.value}`;

    case 'phrase':
      return node.field ? `${node.field}:"${node.text}"` : `"${node.text}"`;

    case 'range': {
      const open = node.lowerInclusive ? '[' : '{';
      const close = node.upperInclusive ? ']' : '}';
      return `${node.field}:${open}${node.lower} TO ${node.upper}${close}`;
    }

    case 'boost':
      return `${prettyPrint(node.child)}^${node.value}`;

    case 'group':
      return `(${prettyPrint(node.child)})`;

    case 'bool':
      return prettyPrintBool(node);
  }
}

function prettyPrintBool(node: BoolNode): string {
  const parts: string[] = [];

  if (node.must.length > 0) {
    parts.push(node.must.map(wrapIfNeeded).join(' AND '));
  }

  if (node.should.length > 0) {
    parts.push(node.should.map(wrapIfNeeded).join(' OR '));
  }

  if (node.must_not.length > 0) {
    for (const child of node.must_not) {
      parts.push(`NOT ${wrapIfNeeded(child)}`);
    }
  }

  return parts.join(' AND ');
}

/** Wrap a child node in parens if it's a BoolNode to preserve precedence. */
function wrapIfNeeded(node: ASTNode): string {
  if (node.type === 'bool') {
    return `(${prettyPrint(node)})`;
  }
  return prettyPrint(node);
}
