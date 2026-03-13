/**
 * Recursive-descent parser for Solr Lucene query syntax.
 *
 * A recursive-descent parser works by defining one function per grammar rule.
 * Each function reads tokens, calls other rule functions as needed, and returns
 * an AST node. The call chain mirrors the grammar structure.
 * (see https://en.wikipedia.org/wiki/Recursive_descent_parser)
 *
 * Grammar rules (each rule becomes a function in the implementation):
 *
 *   query     → orExpr
 *     Entry point. A query is an OR expression.
 *
 *   orExpr    → andExpr ('OR' andExpr)*
 *     One or more AND expressions separated by OR.
 *     Example: `a OR b OR c` → three andExprs joined by OR.
 *     Adjacent terms without an operator are also treated as OR (Solr default).
 *
 *   andExpr   → unaryExpr ('AND' unaryExpr)*
 *     One or more unary expressions separated by AND.
 *     AND is nested inside OR, so it binds tighter.
 *     Example: `a OR b AND c` parses as `a OR (b AND c)`.
 *
 *   unaryExpr → 'NOT' unaryExpr | primary
 *     Either NOT followed by another expression (recursive), or a primary.
 *     NOT is nested inside AND, so it binds tightest of the boolean operators.
 *     Example: `a AND NOT b` parses as `a AND (NOT b)`.
 *
 *   primary   → group | fieldExpr | rangeExpr | phrase | matchAll | value
 *     The smallest building blocks — one of these concrete expressions.
 *
 *   group     → '(' query ')'
 *     Parentheses wrap a full query, overriding precedence.
 *     Example: `(a OR b) AND c`
 *
 *   fieldExpr → FIELD ':' (value | phrase | rangeExpr)
 *     A field name, colon, then a value/phrase/range.
 *     Example: `title:java`, `title:"hello world"`, `price:[10 TO 100]`
 *
 *   rangeExpr → RANGE_START VALUE 'TO' VALUE RANGE_END
 *     A bracketed range with lower and upper bounds.
 *     Example: `[10 TO 100]` (inclusive), `{10 TO 100}` (exclusive)
 *
 *   phrase    → PHRASE
 *     A double-quoted string. Example: `"hello world"`
 *
 *   matchAll  → WILDCARD ':' WILDCARD
 *     The `*:*` pattern, matching all documents.
 *
 *   value     → VALUE | WILDCARD
 *     A bare search term or wildcard. Example: `java`, `*`
 *
 * Operator precedence (highest → lowest):
 *   1. Grouping ()   — evaluated first. `(a OR b) AND c` groups a,b before AND.
 *   2. NOT            — binds tightest of boolean ops. `a AND NOT b` → `a AND (NOT b)`
 *   3. AND            — binds tighter than OR. `a OR b AND c` → `a OR (b AND c)`
 *   4. OR             — binds loosest. Also the implicit operator between adjacent terms.
 *
 * Example: `a OR b AND NOT c` parses as `a OR (b AND (NOT c))`
 */

import type { Token } from '../lexer/lexer';
import type { ParseResult } from './types';

/**
 * Parse a Lucene-syntax token stream into an AST.
 * @param tokens - Token stream from the lexer (must end with EOF)
 * @param params - Request parameters. Lucene parser reads `df` (default field)
 *                 for bare values without a field: prefix.
 */
export function parseLucene(tokens: Token[], params: ReadonlyMap<string, string>): ParseResult {
  // TODO: implement recursive-descent parser following the grammar above
  throw new Error('Not implemented');
}
