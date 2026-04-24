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
 *
 * Only used for explicit field:value syntax. Bare values like `java`
 * produce a BareNode instead.
 */
export interface FieldNode {
  type: 'field';
  field: string;
  value: string;
}

/**
 * Phrase query node — a quoted phrase search with explicit field.
 *
 * Example: `title:"hello world"` → text="hello world", field="title"
 *
 * Only used for explicit field:"phrase" syntax. Bare phrases like `"hello world"`
 * produce a BareNode instead.
 */
export interface PhraseNode {
  type: 'phrase';
  /** The phrase text without quotes. */
  text: string;
  /** The target field (always explicit, e.g., `title:"..."`). */
  field: string;
}

/**
 * Bare query node — a term or phrase without explicit field prefix.
 *
 * Used for queries like `java` or `"hello world"` where no field is specified.
 * The transformer converts this to OpenSearch's query_string query, which
 * searches across the default field (or all fields if df is not set).
 *
 * When queryFields is set (edismax/dismax with qf param), the transformer
 * emits a multi_match across those fields. Each entry is a raw OpenSearch
 * field spec — either "fieldName" or "fieldName^boost" — matching the qf
 * param format directly, since OpenSearch multi_match fields use the same
 * syntax. Takes precedence over defaultField when set.
 *
 * When defaultField is set (df param, standard parser), the transformer
 * emits a query_string with default_field.
 *
 * Examples:
 *   `java` → BareNode { value: "java", isPhrase: false }
 *   `"hello world"` → BareNode { value: "hello world", isPhrase: true }
 *   `java` with df="content" → BareNode { ..., defaultField: "content" }
 *   `java` with qf="title^2 body" → BareNode { ..., queryFields: ["title^2", "body"] }
 */
export interface BareNode {
  type: 'bare';
  /** The search value (term or phrase text, without quotes). */
  value: string;
  /** Whether this is a phrase query (originally quoted). */
  isPhrase: boolean;
  /** The default field from df parameter, or undefined if not set. */
  defaultField?: string;
  /**
   * Query fields from the `qf` parameter (edismax/dismax).
   * Raw field specs in OpenSearch multi_match format: "field" or "field^boost".
   * Takes precedence over defaultField when set.
   */
  queryFields?: string[];
  /**
   * Tie breaker from the `tie` parameter (edismax/dismax).
   * Controls how much non-best-matching fields contribute to the score.
   * Only meaningful when queryFields is set.
   * 0.0 (default) = pure disjunction max, 1.0 = sum of all fields.
   */
  tieBreaker?: number;
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

/**
 * Filter node — Solr's inline filter caching syntax.
 *
 * The filter(...) wrapper tells Solr to cache the inner clause in the filter
 * cache and execute it as a constant-score (non-scoring) clause.
 *
 * Example: `filter(inStock:true)` → child=FieldNode(inStock,true)
 *
 * In OpenSearch, this maps to bool.filter for equivalent non-scoring behavior.
 * See: https://solr.apache.org/guide/solr/latest/query-guide/standard-query-parser.html
 */
export interface FilterNode {
  type: 'filter';
  child: ASTNode;
}

/**
 * A single key-value pair inside a local params block.
 *
 * Example: `qf=title` → { key: 'qf', value: 'title', deref: false }
 * Example: `v=$qq`    → { key: 'v', value: 'qq', deref: true }
 */
export interface LocalParamsPair {
  key: string;
  value: string;
  /** True when the value was a $-prefixed parameter dereference. */
  deref: boolean;
}

/**
 * AST node for a query string with a {!...} local params prefix.
 *
 * The grammar captures the body as a raw string. The parser (parser.ts)
 * inspects the `type` param and re-parses the raw body with the appropriate
 * grammar — e.g., Lucene syntax for lucene/dismax/edismax, or leaves it
 * raw for unsupported parser types.
 *
 * Example: `{!dismax qf=title}java` →
 *   { type: 'localParams',
 *     params: [{ key: 'type', value: 'dismax', deref: false },
 *              { key: 'qf', value: 'title', deref: false }],
 *     rawBody: 'java',
 *     body: BareNode { value: 'java', isPhrase: false } }
 */
export interface LocalParamsNode {
  type: 'localParams';
  /** Parsed key-value pairs from the {!...} block, in order. */
  params: LocalParamsPair[];
  /** Raw body text after the closing `}`, before any re-parsing. */
  rawBody: string | null;
  /** The query body — parsed from rawBody or the v key value. Null when unresolved (e.g., dereference). */
  body: ASTNode | null;
}

/**
 * A numeric literal argument: 42, 3.14, -1, 3.16e-11
 */
export interface NumericLiteralArg {
  kind: 'numeric';
  value: number;
}

/**
 * A field reference argument: popularity, price, date_field
 *
 * Named constants (e.g., NOW) are also parsed as field references —
 * semantic disambiguation is deferred to the transformer layer.
 */
export interface FieldRefArg {
  kind: 'field';
  name: string;
}

/**
 * A single-quoted string constant argument: 'hello world'
 */
export interface StringConstantArg {
  kind: 'string';
  value: string;
}

/**
 * Discriminated union of all function query argument types.
 *
 * Leaf arguments use `kind` as discriminant. Nested function calls are
 * represented directly as FuncNode (discriminated by `type: 'func'`).
 */
export type FuncArg =
  | NumericLiteralArg
  | FieldRefArg
  | StringConstantArg
  | FuncNode;

/**
 * AST node for a Solr function query expression.
 *
 * Example: sum(popularity, 1) →
 *   { type: 'func', name: 'sum', args: [
 *       { kind: 'field', name: 'popularity' },
 *       { kind: 'numeric', value: 1 }
 *   ]}
 */
export interface FuncNode {
  type: 'func';
  /** The function name (e.g., 'sum', 'recip', 'ms'). */
  name: string;
  /** Ordered list of arguments. May be empty for zero-arg functions like now(). */
  args: FuncArg[];
}

/** Union of all AST node types. Every node in the parsed Solr query tree is one of these variants. */
export type ASTNode =
  | BareNode
  | BoolNode
  | FieldNode
  | FilterNode
  | FuncNode
  | PhraseNode
  | RangeNode
  | MatchAllNode
  | GroupNode
  | BoostNode
  | LocalParamsNode;
