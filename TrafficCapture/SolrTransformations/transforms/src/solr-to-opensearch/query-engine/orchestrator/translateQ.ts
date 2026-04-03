/**
 * Orchestrator — wires the Parser → Transformer pipeline.
 *
 * This is the single entry point for translating a Solr query string into
 * OpenSearch Query DSL. It coordinates the pipeline stages:
 *   1. Read `q` from the params map
 *   2. Parse the query string into an AST
 *   3. Transform the AST into OpenSearch DSL Maps
 *
 * Error handling: fail-safe with passthrough.
 * In both modes, the result always contains a `dsl` value and never throws.
 * Issues are reported via the `warnings` array, where each warning includes
 * a machine-readable construct name (e.g., "parse_error", "function_query"),
 * the position in the original query, and a human-readable message. On parse
 * failure or when passthrough-on-error mode encounters an unsupported
 * construct, `dsl` is a query_string passthrough. In partial mode, `dsl`
 * is the partially translated query with unsupported parts skipped.
 *
 * Two modes:
 *   - passthrough-on-error (default): the first unsupported construct stops the
 *     pipeline and falls back to query_string passthrough immediately.
 *   - partial: translates all supported parts of the query
 *     and attaches warnings for unsupported parts.
 */

import { parseSolrQuery } from '../parser/parser';
import { transformNode } from '../transformer/astToOpenSearch';

export interface TranslateResult {
  /**
   * The OpenSearch Query DSL as a nested Map structure.
   * Always present — on error, this is a query_string passthrough.
   */
  dsl: Map<string, any>;
  /**
   * Warnings about unsupported constructs or translation issues.
   * Empty when the translation is fully successful.
   */
  warnings: TranslationWarning[];
}

/**
 * A warning about an unsupported or partially translated construct.
 *
 * Examples:
 *   Parse error:
 *     { construct: "parse_error", position: 5, message: "Unexpected ')' at position 5" }
 *
 *   Unsupported function query:
 *     { construct: "function_query", position: 12, message: "Function queries not yet supported" }
 */
export interface TranslationWarning {
  /** Machine-readable identifier for the issue. */
  construct: string;
  /** 0-based index in the original query where the issue was detected. */
  position?: number;
  /** Human-readable description of the issue and its impact. */
  message: string;
}

/** Controls how the orchestrator handles unsupported constructs. */
export type TranslationMode = 'partial' | 'passthrough-on-error';

/**
 * Build a query_string passthrough DSL Map.
 * Wraps the raw Solr query so OpenSearch can attempt to execute it as-is.
 */
function passthroughDsl(query: string): Map<string, any> {
  return new Map([
    ['query_string', new Map([['query', query]])],
  ]);
}

/**
 * Translate a Solr query into OpenSearch Query DSL.
 *
 * @param params - All Solr request parameters. The orchestrator reads `q`
 *                 from this map and passes the full map to the parser
 *                 for configuration (e.g., `df`, `qf`, `pf`).
 * @param mode - 'passthrough-on-error' (default) or 'partial'.
 *
 * Example:
 *   translateQ(new Map([['q', 'title:java'], ['df', 'content']]))
 *   → { dsl: Map{"match" → Map{"title" → "java"}}, warnings: [] }
 */
export function translateQ(
  params: ReadonlyMap<string, string>,
  mode: TranslationMode = 'passthrough-on-error',
): TranslateResult {
  const query = params.get('q') || '*:*';

  // Stage 1: Parse
  const { ast, errors } = parseSolrQuery(query, params);

  // Parse failure — passthrough regardless of mode (no AST to work with)
  if (ast === null) {
    const warnings: TranslationWarning[] = errors.map((e) => ({
      construct: 'parse_error',
      position: e.position,
      message: e.message,
    }));
    return { dsl: passthroughDsl(query), warnings };
  }

  // Stage 2: Transform
  try {
    const dsl = transformNode(ast);
    return { dsl, warnings: [] };
  } catch (err: unknown) {
    // Transform failure — unsupported node type or unexpected error
    const message = (err as Error).message;
    const warning: TranslationWarning = {
      construct: 'transform_error',
      message,
    };

    if (mode === 'passthrough-on-error') {
      return { dsl: passthroughDsl(query), warnings: [warning] };
    }

    // Partial mode: return what we have (passthrough for now)
    // TODO: implement partial translation of subtrees when more rules exist —
    // walk the AST, translate supported nodes, passthrough unsupported ones.
    return { dsl: passthroughDsl(query), warnings: [warning] };
  }
}
