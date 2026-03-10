/**
 * AST node types for the Solr query parser.
 *
 * Uses discriminated unions — each node carries a `type` string literal
 * discriminant for exhaustive switch-based dispatch in the transformer.
 * This pattern is preferred over class hierarchies because:
 *   1. It survives esbuild bundling (no instanceof checks needed)
 *   2. TypeScript's narrowing gives exhaustive checking in switch statements
 *   3. Nodes are plain data — easy to serialize, compare, and clone
 *
 * The AST is the intermediate representation between parsing and transformation.
 * The parser produces it, the transformer consumes it, and the prettyPrint
 * function can convert it back to Solr syntax for round-trip testing.
 */

/** Boolean query node — maps to OpenSearch `bool` query with must/should/must_not clauses. */
export interface BoolNode {
  type: 'bool';
  /** AND clauses — all children must match. */
  must: ASTNode[];
  /** OR clauses — at least one child should match. Also used for implicit OR (Solr default). */
  should: ASTNode[];
  /** NOT clauses — children must not match. */
  must_not: ASTNode[];
}

/** Field query node — maps to OpenSearch `term` query. Example: `title:java` */
export interface FieldNode {
  type: 'field';
  /** The field name (e.g., "title"). For bare values, this is the default field (df). */
  field: string;
  /** The search value (e.g., "java"). */
  value: string;
}

/** Phrase query node — maps to OpenSearch `match_phrase`. Example: `"hello world"` */
export interface PhraseNode {
  type: 'phrase';
  /** The phrase text without quotes. */
  text: string;
  /** The target field. When omitted, the transformer uses the default field (df). */
  field?: string;
}

/** Range query node — maps to OpenSearch `range` query. Example: `price:[10 TO 100]` */
export interface RangeNode {
  type: 'range';
  field: string;
  lower: string;
  upper: string;
  /** If true, lower bound uses `gte`; if false, uses `gt`. Solr `[` = inclusive, `{` = exclusive. */
  lowerInclusive: boolean;
  /** If true, upper bound uses `lte`; if false, uses `lt`. Solr `]` = inclusive, `}` = exclusive. */
  upperInclusive: boolean;
}

/** Match-all node — maps to OpenSearch `match_all`. Produced by `*:*`. */
export interface MatchAllNode {
  type: 'matchAll';
}

/** Grouping node — preserves operator precedence from parentheses. Transparent to the transformer. */
export interface GroupNode {
  type: 'group';
  child: ASTNode;
}

/** Boost node — wraps a child query with a numeric boost value. Example: `title:java^2` */
export interface BoostNode {
  type: 'boost';
  child: ASTNode;
  /** The boost multiplier. Maps to the `boost` key in OpenSearch DSL. */
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


/**
 * Convert an AST back to canonical Solr query syntax for round-trip testing.
 *
 * This is the inverse of parsing: AST → Solr string. Used to verify that
 * parse(prettyPrint(parse(query))) ≡ parse(query) — the round-trip property.
 *
 * Each node type maps to its canonical Solr representation:
 *   FieldNode   → "field:value"
 *   PhraseNode  → 'field:"text"' or '"text"'
 *   BoolNode    → "A AND B", "A OR B", "NOT A"
 *   RangeNode   → "field:[lower TO upper]" or "field:{lower TO upper}"
 *   BoostNode   → "child^value"
 *   GroupNode   → "(child)"
 *   MatchAllNode → "*:*"
 */
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

/**
 * Pretty-print a BoolNode by joining clauses with their respective operators.
 *
 * The output format follows Solr conventions:
 *   - must clauses are joined with " AND "
 *   - should clauses are joined with " OR "
 *   - must_not clauses are each prefixed with "NOT "
 *   - When multiple clause types are present, they're joined with " AND "
 *     (e.g., "A AND B AND C OR D AND NOT E")
 */
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
