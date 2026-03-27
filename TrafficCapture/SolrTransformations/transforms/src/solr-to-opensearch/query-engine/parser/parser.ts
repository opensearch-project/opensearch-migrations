/**
 * Solr query parser.
 *
 * Parses a Solr query string into an AST matching the types in
 * ../ast/nodes.ts. Uses a PEG grammar (solr.pegjs) internally.
 *
 * The grammar's action blocks return plain JS objects whose shape matches
 * the TypeScript interfaces in nodes.ts. The `type` field is the
 * discriminant for the ASTNode union. Peggy doesn't know about TypeScript
 * — this module casts the result via `as ASTNode`.
 *
 * Grammar rule → AST node mapping:
 *   orExpr / andExpr / unaryExpr → BoolNode
 *   fieldExpr (field:value)      → FieldNode
 *   fieldExpr (field:"text")     → PhraseNode
 *   barePhrase ("text")          → PhraseNode (field="" → resolved to df)
 *   bareValue (text)             → FieldNode  (field="" → resolved to df)
 *   fieldExpr (field:[range])    → RangeNode
 *   matchAll / empty query       → MatchAllNode
 *   group                        → GroupNode
 *   boost suffix (^N)            → BoostNode wrapping the boosted node
 *
 * Trace example for `title:java AND price:[10 TO 100]`:
 *   1. query → orExpr → andExpr
 *   2. andExpr matches: head "AND" tail
 *      - head → fieldExpr matches `title:java`
 *        → { type:'field', field:'title', value:'java' }                 (FieldNode)
 *      - tail → fieldExpr matches `price:[10 TO 100]`
 *        → { type:'range', field:'price', lower:'10', upper:'100',
 *            lowerInclusive:true, upperInclusive:true }                  (RangeNode)
 *   3. andExpr action returns
 *        → { type:'bool', and:[FieldNode, RangeNode], or:[], not:[] }   (BoolNode)
 *
 * Responsibilities:
 *   - Parse the query string into an AST
 *   - Apply the default field (df) from params to bare values and phrases
 *   - Return parse errors as ParseError (never throws)
 */

import * as peggy from 'peggy';
import grammar from './solr.pegjs';
import type { ASTNode } from '../ast/nodes';
import type { ParseResult, ParseError } from './types';

// Lazily compiled parser — the grammar is inlined at build time and compiled
// on the first call to parseSolrQuery, then cached for all subsequent calls.
let parserInstance: peggy.Parser | null = null;

/** Return the cached parser, compiling the grammar on first call. */
function getParser(): peggy.Parser {
  if (parserInstance) return parserInstance;
  parserInstance = peggy.generate(grammar);
  return parserInstance;
}

/**
 * Parse a Solr query string into an AST.
 *
 * @param query - The raw Solr query string (the `q` parameter value)
 * @param params - Request parameters. Reads `df` to resolve bare
 *                 values and unfielded phrases to a default field.
 * @returns ParseResult with the AST and any errors
 *
 * Examples:
 *   parseSolrQuery("title:java AND price:[10 TO 100]", new Map([["df", "content"]]))
 *   → { ast: BoolNode { and: [FieldNode, RangeNode] }, errors: [] }
 *
 *   parseSolrQuery("java", new Map([["df", "title"]]))
 *   → { ast: FieldNode { field: "title", value: "java" }, errors: [] }
 *
 *   parseSolrQuery("title:java AND )", new Map())
 *   → { ast: null, errors: [{ message: "...", position: 16 }] }
 */
export function parseSolrQuery(
  query: string,
  params: ReadonlyMap<string, string>,
): ParseResult {
  // _text_ is Solr's default catch-all field in the _default_ configset.
  // Callers should provide df explicitly when they know the schema's default;
  // _text_ is a safety net for when df is not in the params map.
  const df = params.get('df') || '_text_';

  // q.op controls the default boolean operator for implicit adjacency.
  // When q.op=AND, `title:java title:python` is treated as AND instead of OR.
  // Solr accepts both "AND" and "and", so we normalize to uppercase.
  const qOp = params.get('q.op')?.toUpperCase();

  try {
    const ast = getParser().parse(query) as ASTNode;
    // Apply q.op before resolveDefaultFields — applyDefaultOperator reads
    // the `implicit` flag which resolveDefaultFields cleans up.
    if (qOp === 'AND') {
      applyDefaultOperator(ast);
    }
    resolveDefaultFields(ast, df);
    return { ast, errors: [] };
  } catch (err: unknown) {
    return { ast: null, errors: [toParseError(err)] };
  }
}

/**
 * Convert an unknown caught error into a ParseError.
 * Peggy throws SyntaxError with message and location.start.offset.
 * The fallbacks handle non-peggy errors (e.g., grammar compilation failures).
 */
export function toParseError(err: unknown): ParseError {
  const e = err as Record<string, any>;
  return {
    message: e?.message || 'Parse error',
    position: e?.location?.start?.offset ?? 0,
  };
}

/** Walk the AST and replace empty field strings with the default field.
 *
 * This handles the Lucene parser's single `df` parameter. For eDisMax's
 * multi-field `qf` (e.g., `qf=title^2 content^1`), bare terms need to be
 * expanded into a BoolNode with one clause per qf field — that's a separate
 * post-parse AST transformation, not handled here.
 *
 * Uses an explicit switch over node types instead of duck-typing ('field' in node)
 * to keep full type safety — the compiler catches missing cases when new node
 * types are added to the ASTNode union.
 */
function resolveDefaultFields(node: ASTNode, df: string): void {
  switch (node.type) {
    case 'field':
    case 'phrase':
    case 'range':
      if (node.field === '') node.field = df;
      break;
    case 'bool':
      // Clean up grammar artifact — implicit flag shouldn't leak into AST
      delete (node as any).implicit;
      node.and.forEach((child) => resolveDefaultFields(child, df));
      node.or.forEach((child) => resolveDefaultFields(child, df));
      node.not.forEach((child) => resolveDefaultFields(child, df));
      break;
    case 'boost':
    case 'group':
      resolveDefaultFields(node.child, df);
      break;
    case 'matchAll':
      break;
    // Compile-time exhaustive check, unreachable at runtime.
    // Assigning `node` to `never` causes a compile error if a new type is added
    // to the ASTNode union without adding a corresponding case here. The runtime
    // throw is a safety net in case the compile check is bypassed (e.g., via
    // `as any` casts or untyped data from the peggy grammar).
    /* v8 ignore next 3 */
    default: {
      const _exhaustive: never = node;
      throw new Error(`Unhandled node type: ${(_exhaustive as ASTNode).type}`);
    }
  }
}

/**
 * When q.op=AND, convert implicit-OR BoolNodes to AND.
 *
 * The grammar always treats implicit adjacency (e.g., `title:java title:python`)
 * as OR — Solr's default. When q.op=AND, these implicit-OR nodes should be AND
 * instead. The grammar tags implicit adjacency BoolNodes with `implicit: true`
 * to distinguish them from explicit `OR` operators.
 *
 * BoolNodes with explicit OR (`implicit: false`) are left unchanged.
 */
function applyDefaultOperator(node: ASTNode): void {
  switch (node.type) {
    case 'bool': {
      // Only convert if this BoolNode was produced by implicit adjacency,
      // not by an explicit OR keyword.
      const boolNode = node as ASTNode & { implicit?: boolean };
      if (boolNode.implicit && node.or.length > 0) {
        node.and = node.or;
        node.or = [];
      }
      // Recurse into all children
      node.and.forEach((child) => applyDefaultOperator(child));
      node.or.forEach((child) => applyDefaultOperator(child));
      node.not.forEach((child) => applyDefaultOperator(child));
      break;
    }
    case 'boost':
    case 'group':
      applyDefaultOperator(node.child);
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

