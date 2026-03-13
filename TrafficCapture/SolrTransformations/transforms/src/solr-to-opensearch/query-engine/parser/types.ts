/**
 * Shared types for all parser implementations.
 *
 * These types define the contract that every parser must follow,
 * regardless of which Solr query syntax it handles.
 */

import type { ASTNode } from '../ast/nodes';
import type { Token } from '../lexer/lexer';

/**
 * The result of parsing a token stream.
 */
export interface ParseResult {
  /** The parsed AST. */
  ast: ASTNode;
  /** Errors encountered during parsing. Empty when parsing succeeds. */
errors: ParseError[];
}

/**
 * An error encountered during parsing.
 *
 * Example: unexpected `)` at position 0
 *   → message="Unexpected token RPAREN (')')", token={type:"RPAREN",...}, position=0
 */
export interface ParseError {
  message: string;
  /** The token that caused the error. Always present since the parser operates on tokens, not raw strings. */
  token: Token;
  /** 0-based index in the query string where the error occurred. */
  position: number;
}
