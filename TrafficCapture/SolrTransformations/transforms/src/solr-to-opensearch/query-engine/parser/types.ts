/**
 * Shared types for the parser layer.
 */

import type { ASTNode } from '../ast/nodes';

/**
 * The result of parsing a Solr query string.
 */
export interface ParseResult {
  /** The parsed AST, or null when parsing fails. */
  ast: ASTNode | null;
  /** Errors encountered during parsing. Empty when parsing succeeds. */
  errors: ParseError[];
}

/**
 * An error encountered during parsing.
 *
 * Example: parsing `title:java AND )` fails because `)` appears
 * where an expression is expected (position 16 is the `)` character).
 *   → { message: "Expected field, phrase, or value after 'AND'", position: 16 }
 */
export interface ParseError {
  message: string;
  /** 0-based index in the query string where the error occurred. */
  position: number;
}
