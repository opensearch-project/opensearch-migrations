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
 *   barePhrase ("text")          → BareNode (isPhrase=true)
 *   bareValue (text)             → BareNode (isPhrase=false)
 *   fieldExpr (field:[range])    → RangeNode
 *   matchAll / empty query       → MatchAllNode
 *   group                        → GroupNode
 *   boost suffix (^N)            → BoostNode wrapping the boosted node

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
 * Post-parse passes (applied in order):
 *   1. applyDefaultOperator  — q.op=AND: convert implicit-OR BoolNodes to AND
 *   2. resolveDefaultFields  — df → BareNode.defaultField
 *   3. applyQueryFields      — qf → BareNode.queryFields (edismax/dismax only)
 *
 * Responsibilities:
 *   - Parse the query string into an AST
 *   - Orchestrate post-parse AST normalization passes
 *   - Return parse errors as ParseError (never throws)
 */

import * as peggy from 'peggy';
import grammar from './solr.pegjs';
import type { ASTNode } from '../ast/nodes';
import type { ParseResult, ParseError } from './types';
import { applyQueryFields } from './qf';

// Lazily compiled parser — the grammar is inlined at build time and compiled
// on the first call to parseSolrQuery, then cached for all subsequent calls.
let parserInstance: peggy.Parser | null = null;

/** Return the cached parser, compiling the grammar on first call. */
function getParser(): peggy.Parser {
  if (parserInstance) return parserInstance;
  parserInstance = peggy.generate(grammar, { allowedStartRules: ['query', 'funcQuery'] });
  return parserInstance;
}

/**
 * Parse a Solr query string into an AST.
 *
 * @param query - The raw Solr query string (the `q` parameter value)
 * @param params - Request parameters. Reads `df`, `q.op`, `defType`, `qf`.
 * @returns ParseResult with the AST and any errors
 *
 * Examples:
 *   parseSolrQuery("title:java AND price:[10 TO 100]", new Map([["df", "content"]]))
 *   → { ast: BoolNode { and: [FieldNode, RangeNode] }, errors: [] }
 *
 *   parseSolrQuery("java", new Map([["df", "title"]]))
 *   → { ast: BareNode { value: "java", defaultField: "title" }, errors: [] }
 *
 *   parseSolrQuery("java", new Map([["defType", "edismax"], ["qf", "title^2 body"]]))
 *   → { ast: BareNode { value: "java", queryFields: [{field:"title",boost:2},{field:"body"}] }, errors: [] }
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

  // defType selects edismax/dismax semantics for the qf pass.
  const defType = params.get('defType')?.toLowerCase();

  try {
    const ast = getParser().parse(query) as ASTNode;

    // Phase 2: If the grammar produced a LocalParamsNode, re-parse the body
    // based on the query parser type. The grammar captures the body as raw
    // text; we parse it here with the appropriate grammar.
    if (ast.type === 'localParams') {
      resolveLocalParamsBody(ast);
    }

    // Apply q.op before resolveDefaultFields — applyDefaultOperator reads
    // the `implicit` flag which resolveDefaultFields cleans up.
    if (qOp === 'AND') {
      applyDefaultOperator(ast);
    }
    resolveDefaultFields(ast, df);

    // qf → BareNode.queryFields (edismax/dismax only)
    if (defType === 'edismax' || defType === 'dismax') {
      applyQueryFields(ast, params);
    }

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

/**
 * Parse a Solr function query string (e.g., "avg(price)") into a FuncNode.
 *
 * Uses the `funcQuery` start rule. Throws on parse failure.
 */
export function parseFuncQuery(input: string): import('../ast/nodes').FuncNode {
  return getParser().parse(input, { startRule: 'funcQuery' }) as import('../ast/nodes').FuncNode;
}

/**
 * Query parser types whose body uses Lucene/Solr query syntax.
 * For these types, the raw body is re-parsed using the Solr grammar.
 * Other types (e.g., func, terms) need their own parsers.
 */
const LUCENE_FAMILY_TYPES = new Set([
  'lucene', 'dismax', 'edismax',
]);

/**
 * Resolve the body of a LocalParamsNode.
 *
 * The grammar captures the body as raw text. This function determines the
 * effective body string (from the `v` key or the trailing raw text), then
 * re-parses it using the appropriate grammar based on the `type` param.
 *
 * For Lucene-family types (lucene, dismax, edismax, or no type specified),
 * the body is parsed with the Solr grammar. For other types, the body is
 * left as null — future parsers (e.g., function query) will handle them.
 *
 * @throws Re-throws parse errors from body re-parsing so the caller can
 *         convert them to ParseError via toParseError().
 */
function resolveLocalParamsBody(node: import('../ast/nodes').LocalParamsNode): void {
  const vPair = node.params.find((p) => p.key === 'v');

  // Dereference — can't resolve at parse time
  if (vPair?.deref) {
    node.body = null;
    return;
  }

  // Determine the effective body string: v key takes precedence over raw body
  const bodyStr = vPair ? vPair.value : node.rawBody;
  if (!bodyStr) {
    node.body = null;
    return;
  }

  // Determine the parser type (short form or explicit type= pair)
  const typePair = node.params.find((p) => p.key === 'type');
  const parserType = typePair?.value?.toLowerCase();

  // For Lucene-family types (or no type = default Lucene), re-parse with Solr grammar
  if (!parserType || LUCENE_FAMILY_TYPES.has(parserType)) {
    node.body = getParser().parse(bodyStr) as import('../ast/nodes').ASTNode;
    return;
  }

  // Function query type — parse with the funcQuery start rule
  if (parserType === 'func') {
    node.body = getParser().parse(bodyStr, { startRule: 'funcQuery' }) as import('../ast/nodes').ASTNode;
    return;
  }

  // Unknown parser type — leave body null for now.
  node.body = null;
}

/** Walk the AST and resolve BareNode default fields.
 *
 * For BareNode (bare terms/phrases), sets the defaultField property
 * based on the df parameter. When df is '_text_' (Solr's default catch-all),
 * leaves defaultField undefined so the transformer omits it from the output.
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
      // These nodes have explicit fields from the grammar, nothing to resolve
      break;
    case 'bare':
      // Set defaultField only if df is not the Solr catch-all
      if (df !== '_text_') {
        node.defaultField = df;
      }
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
    case 'filter':
      resolveDefaultFields(node.child, df);
      break;
    case 'localParams':
      if (node.body) resolveDefaultFields(node.body, df);
      break;
    case 'func':
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
    case 'filter':
      applyDefaultOperator(node.child);
      break;
    case 'localParams':
      if (node.body) applyDefaultOperator(node.body);
      break;
    case 'bare':
    case 'field':
    case 'func':
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
