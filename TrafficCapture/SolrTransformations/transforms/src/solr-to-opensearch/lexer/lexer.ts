/**
 * Lexer for Solr query strings.
 *
 * Converts a raw Solr query string into a typed token stream
 * for consumption by the parser. This is the first stage of the
 * Lexer → Parser → AST → Transformer pipeline.
 *
 * Key design decisions:
 *   - Cursor-based: maintains a `pos` index that advances through the string
 *   - One-token lookahead: after consuming an unquoted word, peeks at the next
 *     character to distinguish FIELD tokens (followed by `:`) from VALUE tokens
 *   - Whitespace is consumed but never emitted as tokens (Solr treats whitespace
 *     as a delimiter, not a syntactic element)
 *   - Keywords (AND, OR, NOT, TO) are only recognized when followed by a
 *     delimiter (whitespace, `:`, `(`, or EOF) — this prevents "ANDROID" from
 *     being split into "AND" + "ROID"
 *   - Errors are collected (not thrown) so the lexer can report multiple issues
 *     and the translator can fall back to query_string passthrough
 *
 * Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 9.1
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

export interface Token {
  type: TokenType;
  value: string;
  position: number;
}

export interface LexerResult {
  tokens: Token[];
  errors: LexerError[];
}

export interface LexerError {
  message: string;
  position: number;
}

/** Characters that can appear in an unquoted value. */
const VALUE_CHARS = /[a-zA-Z0-9._\-*]/;

/** Keywords that are recognized as operators when followed by a delimiter. */
const KEYWORDS: Record<string, TokenType> = {
  AND: 'AND',
  OR: 'OR',
  NOT: 'NOT',
  TO: 'TO',
};

/**
 * Returns true if the character at `pos` is a keyword delimiter —
 * whitespace, `:`, `(`, or end-of-string.
 */
function isKeywordBoundary(query: string, pos: number): boolean {
  if (pos >= query.length) return true;
  const ch = query[pos];
  return ch === ' ' || ch === '\t' || ch === '\n' || ch === '\r' || ch === ':' || ch === '(';
}

/** Tokenize a raw Solr query string. */
export function tokenize(query: string): LexerResult {
  const tokens: Token[] = [];
  const errors: LexerError[] = [];
  let pos = 0;

  while (pos < query.length) {
    // Skip whitespace (Req 1.3: no whitespace tokens)
    if (/\s/.test(query[pos])) {
      pos++;
      continue;
    }

    const ch = query[pos];

    // Double-quoted phrase (Req 1.4)
    if (ch === '"') {
      const start = pos;
      pos++; // skip opening quote
      let text = '';
      while (pos < query.length && query[pos] !== '"') {
        text += query[pos];
        pos++;
      }
      if (pos >= query.length) {
        // Unterminated quote (Req 1.6)
        errors.push({
          message: `Unterminated quote starting at position ${start}`,
          position: start,
        });
      } else {
        pos++; // skip closing quote
      }
      tokens.push({ type: 'PHRASE', value: text, position: start });
      continue;
    }

    // Single-character tokens
    if (ch === '[') {
      tokens.push({ type: 'INCLUSIVE_RANGE_START', value: '[', position: pos });
      pos++;
      continue;
    }
    if (ch === '{') {
      tokens.push({ type: 'EXCLUSIVE_RANGE_START', value: '{', position: pos });
      pos++;
      continue;
    }
    if (ch === ']') {
      tokens.push({ type: 'INCLUSIVE_RANGE_END', value: ']', position: pos });
      pos++;
      continue;
    }
    if (ch === '}') {
      tokens.push({ type: 'EXCLUSIVE_RANGE_END', value: '}', position: pos });
      pos++;
      continue;
    }
    if (ch === '(') {
      tokens.push({ type: 'LPAREN', value: '(', position: pos });
      pos++;
      continue;
    }
    if (ch === ')') {
      tokens.push({ type: 'RPAREN', value: ')', position: pos });
      pos++;
      continue;
    }
    if (ch === ':') {
      tokens.push({ type: 'COLON', value: ':', position: pos });
      pos++;
      continue;
    }

    // Boost: ^ followed by digits/dot
    if (ch === '^') {
      const start = pos;
      pos++; // skip ^
      let numStr = '';
      while (pos < query.length && /[0-9.]/.test(query[pos])) {
        numStr += query[pos];
        pos++;
      }
      tokens.push({ type: 'BOOST', value: numStr, position: start });
      continue;
    }

    // Wildcard: standalone *
    // But we also need to handle * inside VALUE tokens (e.g., "te*t")
    // A standalone * is when it's not adjacent to value chars on both sides
    if (ch === '*' && (pos + 1 >= query.length || !VALUE_CHARS.test(query[pos + 1]) || query[pos + 1] === '*')) {
      // Check if previous token makes this part of a value
      // Standalone wildcard
      if (tokens.length > 0 && tokens[tokens.length - 1].type === 'VALUE') {
        // Append to previous value token (e.g., "test*")
        // Actually, * at end of value is consumed during VALUE parsing below
        // This branch handles truly standalone *
      }
      tokens.push({ type: 'WILDCARD', value: '*', position: pos });
      pos++;
      continue;
    }

    // Value or keyword
    if (VALUE_CHARS.test(ch)) {
      const start = pos;
      let word = '';
      while (pos < query.length && VALUE_CHARS.test(query[pos])) {
        word += query[pos];
        pos++;
      }

      // Check if this word is a keyword (AND, OR, NOT, TO)
      const keywordType = KEYWORDS[word];
      if (keywordType && isKeywordBoundary(query, pos)) {
        tokens.push({ type: keywordType, value: word, position: start });
        continue;
      }

      // Check lookahead: if next char is ':', classify as FIELD
      if (pos < query.length && query[pos] === ':') {
        tokens.push({ type: 'FIELD', value: word, position: start });
      } else {
        tokens.push({ type: 'VALUE', value: word, position: start });
      }
      continue;
    }

    // Unrecognized character (Req 9.1)
    errors.push({
      message: `Unrecognized character '${ch}' at position ${pos}`,
      position: pos,
    });
    pos++;
  }

  // Emit EOF
  tokens.push({ type: 'EOF', value: '', position: pos });

  return { tokens, errors };
}
