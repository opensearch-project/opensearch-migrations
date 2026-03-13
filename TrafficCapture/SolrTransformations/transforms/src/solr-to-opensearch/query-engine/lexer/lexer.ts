/**
 * Lexer for Solr query strings.
 *
 * Converts a raw Solr query string into a typed token stream for consumption
 * by the parser. This is the first stage of the query engine pipeline.
 *
 * Key design decisions:
 *   - Cursor-based: maintains a position index that walks through the string
 *     one character at a time, left to right
 *     (see https://en.wikipedia.org/wiki/Lexical_analysis#Scanner)
 *   - One-token lookahead: after reading a word like `title`, peeks at the next
 *     character to decide its type — if followed by `:` it's a FIELD, otherwise a VALUE
 *   - Whitespace is consumed but never emitted as tokens
 *   - Keywords (AND, OR, NOT, TO) are only recognized when followed by a
 *     delimiter (whitespace, `:`, `(`, or EOF)
 *   - Errors are collected (not thrown) so the orchestrator can fall back gracefully
 */

/**
 * All token types produced by the lexer.
 *
 * Each token type corresponds to a syntactic element in Solr query syntax:
 *   FIELD                 — a word followed by `:`, identifying a field name. `title` in `title:java`
 *   COLON                 — the `:` separator between field and value
 *   VALUE                 — a word NOT followed by `:`, representing a search term. `java` in `title:java`
 *   AND                   — boolean AND operator. `AND` in `title:java AND author:smith`
 *   OR                    — boolean OR operator. `OR` in `title:java OR title:python`
 *   NOT                   — boolean NOT operator. `NOT` in `NOT title:java`
 *   TO                    — range separator keyword. `TO` in `[10 TO 100]`
 *   LPAREN                — opening parenthesis. `(` in `(title:java OR title:python)`
 *   RPAREN                — closing parenthesis. `)` in `(title:java OR title:python)`
 *   PHRASE                — double-quoted string, quotes excluded. `"hello world"` in `title:"hello world"`
 *   INCLUSIVE_RANGE_START  — `[` bracket, inclusive lower bound. `[` in `price:[10 TO 100]`
 *   EXCLUSIVE_RANGE_START  — `{` bracket, exclusive lower bound. `{` in `price:{10 TO 100}`
 *   INCLUSIVE_RANGE_END    — `]` bracket, inclusive upper bound. `]` in `price:[10 TO 100]`
 *   EXCLUSIVE_RANGE_END    — `}` bracket, exclusive upper bound. `}` in `price:{10 TO 100}`
 *   BOOST                 — caret followed by a number. `^2` in `title:java^2` → value is `2`
 *   WILDCARD              — standalone `*` character. `*` in `*:*`
 *   EOF                   — end of input
 */
export type TokenType =
  | 'FIELD'
  | 'COLON'
  | 'VALUE'
  | 'AND'
  | 'OR'
  | 'NOT'
  | 'LPAREN'
  | 'RPAREN'
  | 'PHRASE'
  | 'INCLUSIVE_RANGE_START'
  | 'EXCLUSIVE_RANGE_START'
  | 'INCLUSIVE_RANGE_END'
  | 'EXCLUSIVE_RANGE_END'
  | 'TO'
  | 'BOOST'
  | 'WILDCARD'
  | 'EOF';

/**
 * A single token produced by the lexer.
 *
 * Example: for input `title:java`, the lexer produces:
 *   { type: "FIELD", value: "title", position: 0 }
 *   { type: "COLON", value: ":",     position: 5 }
 *   { type: "VALUE", value: "java",  position: 6 }
 *   { type: "EOF",   value: "",      position: 10 }
 */
export interface Token {
  type: TokenType;
  /** The raw text of the token. For PHRASE, this is the text between quotes (without the quotes). */
  value: string;
  /** Character offset in the original query string. */
  position: number;
}

/**
 * Result of tokenizing a Solr query string.
 *
 * On success: tokens contains the full token stream (ending with EOF), errors is empty.
 * On failure: tokens may be partial, errors contains one or more LexerError entries.
 */
export interface LexerResult {
  tokens: Token[];
  errors: LexerError[];
}

/**
 * An error encountered during tokenization.
 *
 * Examples:
 *   `"unterminated`  → message="Unterminated quote starting at position 0", position=0
 *   `title~java`    → message="Unrecognized character '~' at position 5", position=5
 */
export interface LexerError {
  message: string;
  /** 0-based index in the query string where the error occurred. */
  position: number;
}

/**
 * Tokenize a raw Solr query string.
 *
 * Example:
 *   tokenize("title:java AND price:[10 TO 100]")
 *   → tokens: [
 *       { type: "FIELD", value: "title", position: 0 },
 *       { type: "COLON", value: ":", position: 5 },
 *       { type: "VALUE", value: "java", position: 6 },
 *       { type: "AND", value: "AND", position: 11 },
 *       { type: "FIELD", value: "price", position: 15 },
 *       { type: "COLON", value: ":", position: 20 },
 *       { type: "INCLUSIVE_RANGE_START", value: "[", position: 21 },
 *       { type: "VALUE", value: "10", position: 22 },
 *       { type: "TO", value: "TO", position: 25 },
 *       { type: "VALUE", value: "100", position: 28 },
 *       { type: "INCLUSIVE_RANGE_END", value: "]", position: 31 },
 *       { type: "EOF", value: "", position: 32 }
 *     ]
 *     errors: []
 */
export function tokenize(query: string): LexerResult {
  // TODO: implement cursor-based tokenizer
  throw new Error('Not implemented');
}
