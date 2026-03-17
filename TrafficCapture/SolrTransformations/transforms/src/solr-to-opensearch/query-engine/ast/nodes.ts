/**
 * AST node types for the Solr query parser.
 *
 * Represents the parsed structure of a Solr query as a tree of typed nodes.
 * Each node carries a `type` string literal discriminant that identifies
 * its variant (TypeScript discriminated union pattern).
 *
 * The AST is purely a Solr query representation — it describes what the
 * user wrote, not how it will be executed by any search engine.
 */

/**
 * Boolean query node — represents Solr's AND/OR/NOT boolean operators.
 *
 * Example: `title:java AND author:smith OR year:2024 NOT status:draft`
 *   → and: [FieldNode(title,java), FieldNode(author,smith)]
 *   → or: [FieldNode(year,2024)]
 *   → not: [FieldNode(status,draft)]
 */
export interface BoolNode {
  type: 'bool';
  /** AND clauses. `a AND b AND c` → and: [a, b, c] */
  and: ASTNode[];
  /** OR clauses. `a OR b` → or: [a, b]. Also used for implicit OR: `a b` → or: [a, b] */
  or: ASTNode[];
  /** NOT clauses. `NOT a` → not: [a] */
  not: ASTNode[];
}

/**
 * Field-value query node — a `field:value` expression in Solr syntax.
 *
 * Example: `title:java` → field="title", value="java"
 */
export interface FieldNode {
  type: 'field';
  field: string;
  value: string;
}

/**
 * Phrase query node — a quoted phrase search.
 *
 * Examples:
 *   `title:"hello world"` → text="hello world", field="title"
 *   `"hello world"` with df="content" → text="hello world", field="content"
 */
export interface PhraseNode {
  type: 'phrase';
  /** The phrase text without quotes. */
  text: string;
  /**
   * The target field. The parser sets this to the explicit field (e.g., `title:"..."`)
   * or the default field (df) for bare phrases like `"hello world"`.
   */
  field: string;
}

/**
 * Range query node — a bounded range search.
 *
 * Examples:
 *   `price:[10 TO 100]` → field="price", lower="10", upper="100", lowerInclusive=true, upperInclusive=true
 *   `price:{10 TO 100}` → field="price", lower="10", upper="100", lowerInclusive=false, upperInclusive=false
 *   `price:[10 TO 100}` → field="price", lower="10", upper="100", lowerInclusive=true, upperInclusive=false
 */
export interface RangeNode {
  type: 'range';
  field: string;
  lower: string;
  upper: string;
  /** true when lower bound uses `[` (value included), false when `{` (value excluded). */
  lowerInclusive: boolean;
  /** true when upper bound uses `]` (value included), false when `}` (value excluded). */
  upperInclusive: boolean;
}

/**
 * Match-all node — matches every document.
 *
 * Example: `*:*` → MatchAllNode (no properties)
 */
export interface MatchAllNode {
  type: 'matchAll';
}

/**
 * Grouping node — preserves operator precedence from parentheses.
 *
 * Example: `(title:java OR title:python)` → child=BoolNode(or: [FieldNode, FieldNode])
 */
export interface GroupNode {
  type: 'group';
  child: ASTNode;
}

/**
 * Boost node — applies a relevance boost to a sub-expression.
 *
 * Example: `title:java^2` → child=FieldNode(title,java), value=2
 */
export interface BoostNode {
  type: 'boost';
  child: ASTNode;
  value: number;
}

/** Union of all AST node types. Every node in the parsed Solr query tree is one of these variants. */
export type ASTNode =
  | BoolNode
  | FieldNode
  | PhraseNode
  | RangeNode
  | MatchAllNode
  | GroupNode
  | BoostNode;
