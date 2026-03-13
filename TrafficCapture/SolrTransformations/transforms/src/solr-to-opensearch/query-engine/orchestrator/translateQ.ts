/**
 * Orchestrator — wires the full Lexer → Parser → Transformer pipeline.
 *
 * This is the single entry point for translating a Solr query string into
 * OpenSearch Query DSL. It coordinates the pipeline stages:
 *   1. Read `q` and `defType` from the params map
 *   2. Tokenize the query string via the lexer
 *   3. Select and invoke the appropriate parser (passing the params map)
 *   4. Transform the AST into OpenSearch DSL Maps
 *
 * Error handling: fail-safe with passthrough.
 * Any error at ANY stage produces a `query_string` passthrough — the raw
 * Solr query is wrapped in {"query_string": {"query": "..."}} and sent to
 * OpenSearch as-is. The result always contains a `dsl` value; issues are
 * reported as warnings, not thrown exceptions.
 *
 * Two modes:
 *   - best-effort (default): translates all supported parts of the query
 *     and attaches warnings for unsupported parts.
 *   - strict: the first unsupported construct stops the pipeline and
 *     falls back to passthrough immediately.
 */

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
 *   Unsupported defType:
 *     { construct: "defType:dismax", position: 0, message: "Unsupported defType 'dismax'" }
 *
 *   Unterminated quote in query:
 *     { construct: "lexer_error", position: 5, message: "Unterminated quote at position 5" }
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
export type TranslationMode = 'best-effort' | 'strict';

/**
 * Translate a Solr query into OpenSearch Query DSL.
 *
 * @param params - All Solr request parameters. The orchestrator reads `q` and
 *                 `defType` from this map, and passes it through to the parser.
 * @param mode - 'best-effort' (default) or 'strict'.
 *
 * Example:
 *   const params = new Map([['q', 'title:java'], ['df', 'content']]);
 *   translateQ(params) → { dsl: Map{"term" → Map{"title" → "java"}}, warnings: [] }
 */
export function translateQ(
  params: ReadonlyMap<string, string>,
  mode?: TranslationMode,
): TranslateResult {
  // TODO: implement
  throw new Error('Not implemented');
}
