/**
 * Translator — orchestrates the full Lexer → Parser → Transformer pipeline.
 *
 * This is the single entry point for translating a Solr `q` parameter into
 * OpenSearch Query DSL. The micro-transforms (query-q, filter-fq) call this
 * function rather than invoking the lexer/parser/transformer directly.
 *
 * Pipeline stages:
 *   1. Check defType → unsupported types fall back immediately
 *   2. Tokenize the query string via the lexer
 *   3. Parse tokens into an AST (Lucene or eDisMax based on defType)
 *   4. Transform the AST into OpenSearch DSL Maps
 *
 * Error handling strategy: fail-safe with passthrough.
 * Any error at ANY stage produces a `query_string` passthrough:
 *   Map{"query_string" → Map{"query" → rawQ}}
 * This lets OpenSearch attempt to parse the raw query itself — a safe
 * degradation that preserves the existing behavior. Errors are never thrown
 * to the caller; they're captured as structured warnings.
 *
 * Two modes are supported:
 *   - best-effort (default): translate what we can, warn about the rest
 *   - strict: fail on the first unsupported construct
 *
 * Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 9.3, 15.1, 15.2, 15.3, 15.4, 15.5
 */

import { tokenize } from '../lexer/lexer';
import { selectParser } from '../parser/parserSelector';
import { parseLucene } from '../parser/luceneParser';
import { parseEdismax } from '../parser/edismaxParser';
import { transformNode } from '../transformer/astToOpenSearch';

export interface TranslateOptions {
  q: string;
  defType?: string;
  qf?: string;
  pf?: string;
  df?: string;
}

export interface TranslateResult {
  dsl: Map<string, any>;
  warnings: TranslationWarning[];
}

export interface TranslationWarning {
  construct: string;
  position?: number;
  message: string;
}

export interface TranslationConfig {
  mode: 'best-effort' | 'strict';
}

/** Build the query_string passthrough fallback DSL. */
function queryStringPassthrough(q: string): Map<string, any> {
  return new Map([['query_string', new Map([['query', q]])]]);
}

/**
 * Full pipeline: lex → parse → transform.
 * Falls back to query_string passthrough on any error.
 */
export function translateQ(opts: TranslateOptions, config?: TranslationConfig): TranslateResult {
  const { q, defType, qf, pf, df } = opts;
  const mode = config?.mode ?? 'best-effort';
  const warnings: TranslationWarning[] = [];
  const defaultField = df || '_text_';

  try {
    // Step 1: Check if defType is unsupported
    const parser = selectParser(defType);
    if (parser === null && defType !== 'edismax') {
      warnings.push({
        construct: `defType:${defType}`,
        position: 0,
        message: `Unsupported defType '${defType}'. Falling back to query_string passthrough.`,
      });
      return { dsl: queryStringPassthrough(q), warnings };
    }

    // Step 2: Tokenize
    const lexerResult = tokenize(q);
    if (lexerResult.errors.length > 0) {
      if (mode === 'strict') {
        warnings.push({
          construct: 'lexer_error',
          position: lexerResult.errors[0].position,
          message: lexerResult.errors[0].message,
        });
        return { dsl: queryStringPassthrough(q), warnings };
      }
      // best-effort: add warnings for lexer errors, fall back
      for (const err of lexerResult.errors) {
        warnings.push({
          construct: 'lexer_error',
          position: err.position,
          message: err.message,
        });
      }
      return { dsl: queryStringPassthrough(q), warnings };
    }

    // Step 3: Parse
    let parseResult;
    if (defType === 'edismax') {
      parseResult = parseEdismax(lexerResult.tokens, {
        qf: qf || '',
        pf,
        df: defaultField,
      });
    } else {
      parseResult = parseLucene(lexerResult.tokens, defaultField);
    }

    if (parseResult.errors.length > 0) {
      if (mode === 'strict') {
        warnings.push({
          construct: 'parser_error',
          position: parseResult.errors[0].position,
          message: parseResult.errors[0].message,
        });
        return { dsl: queryStringPassthrough(q), warnings };
      }
      // best-effort: add warnings for parse errors, fall back
      for (const err of parseResult.errors) {
        warnings.push({
          construct: 'parser_error',
          position: err.position,
          message: err.message,
        });
      }
      return { dsl: queryStringPassthrough(q), warnings };
    }

    // Step 4: Transform AST → OpenSearch DSL
    const dsl = transformNode(parseResult.ast, defaultField);
    return { dsl, warnings };
  } catch (err: any) {
    // Step 5: Any thrown exception → catch, log, return passthrough
    console.error('[translateQ] Pipeline error:', err?.message || err);
    warnings.push({
      construct: 'pipeline_error',
      position: 0,
      message: err?.message || 'Unknown pipeline error',
    });
    return { dsl: queryStringPassthrough(q), warnings };
  }
}
