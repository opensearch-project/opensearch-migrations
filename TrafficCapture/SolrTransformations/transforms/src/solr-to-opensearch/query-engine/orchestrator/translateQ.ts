/**
 * Orchestrator — wires the Parser → Transformer pipeline.
 *
 * This is the single entry point for translating a Solr query string into
 * OpenSearch Query DSL. It coordinates the pipeline stages:
 *   1. Read `q` from the params map
 *   2. Parse the query string into an AST
 *   3. Transform the AST into OpenSearch DSL Maps
 *
 * Error handling depends on the translation mode:
 *   - fail-fast (default): throws on any error. The caller must handle the exception.
 *   - passthrough-on-error: the result always contains a `dsl` value and never
 *     throws. Issues are reported via the `warnings` array, where each warning
 *     includes a machine-readable construct name (e.g., "parse_error",
 *     "transform_error"), the position in the original query, and a human-readable
 *     message. On parse failure or unsupported construct, `dsl` is a query_string
 *     passthrough.
 *
 * Two modes:
 *   - fail-fast (default): throws on any error (parse failure or transform failure).
 *     Use for validation or testing where errors should not be silently
 *     swallowed. The caller is responsible for catching the thrown error.
 *   - passthrough-on-error: the first unsupported construct stops the
 *     pipeline and falls back to query_string passthrough immediately.
 */

import { parseSolrQuery } from '../parser/parser';
import { transformNode } from '../transformer/astToOpenSearch';
import { applyPhraseBoost } from '../transformer/phraseBoost';

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
 * A warning about an unsupported construct or translation issue.
 *
 * Examples:
 *   Parse error:
 *     { construct: "parse_error", position: 16, message: "Expected expression" }
 *
 *   Transform error (unsupported node type):
 *     { construct: "transform_error", message: "No transform rule registered for node type: field" }
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
export type TranslationMode = 'passthrough-on-error' | 'fail-fast';

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
 * @param mode - 'fail-fast' (default) or 'passthrough-on-error'.
 *
 * Example:
 *   translateQ(new Map([['q', 'title:java'], ['df', 'content']]))
 *   → { dsl: Map{"match" → Map{"title" → "java"}}, warnings: [] }
 */
export function translateQ(
  params: ReadonlyMap<string, string>,
  mode: TranslationMode = 'fail-fast',
): TranslateResult {
  const query = params.get('q') || '*:*';

  // Stage 1: Parse
  const { ast, errors } = parseSolrQuery(query, params);

  // Parse failure — no AST to work with
  if (ast === null) {
    console.error(`[translateQ] Parse failure for query: ${query}`, errors);

    if (mode === 'fail-fast') {
      throw new Error(`Failed to parse Solr query: ${query}`);
    }

    const warnings: TranslationWarning[] = errors.map((e) => ({
      construct: 'parse_error',
      position: e.position,
      message: e.message,
    }));
    return { dsl: passthroughDsl(query), warnings };
  }

  // Stage 2: Transform
  try {
    const mainDsl = transformNode(ast);

    // Stage 3: pf phrase boost — wraps the main query in bool.must + bool.should
    // if pf is set. Delegated to phraseBoost.ts (transformer concern).
    const dsl = applyPhraseBoost(mainDsl, query, params);
    return { dsl, warnings: [] };
  } catch (err: unknown) {
    // Transform failure — unsupported node type or unexpected error
    console.error(`[translateQ] Transform failure for query: ${query}`, err);

    if (mode === 'fail-fast') {
      throw new Error(`Failed to transform Solr query: ${query}`);
    }

    const message = (err as Error).message;
    const warning: TranslationWarning = {
      construct: 'transform_error',
      message,
    };

    return { dsl: passthroughDsl(query), warnings: [warning] };
  }
}
