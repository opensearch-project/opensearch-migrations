/**
 * Recursive-descent parser for Solr Lucene query syntax.
 *
 * Grammar:
 *   query       → orExpr
 *   orExpr      → andExpr ('OR' andExpr)*
 *   andExpr     → unaryExpr ('AND' unaryExpr)*
 *   unaryExpr   → 'NOT' unaryExpr | primary
 *   primary     → group | fieldExpr | rangeExpr | phrase | matchAll | value
 *   group       → '(' query ')'
 *   fieldExpr   → FIELD ':' (value | phrase | rangeExpr)
 *   rangeExpr   → RANGE_START VALUE 'TO' VALUE RANGE_END
 *   phrase      → PHRASE
 *   matchAll    → WILDCARD ':' WILDCARD
 *   value       → VALUE | WILDCARD
 *
 * Operator precedence (highest → lowest):
 *   1. Grouping ()
 *   2. NOT (unary prefix)
 *   3. AND (binary, left-associative)
 *   4. OR (binary, left-associative)
 *
 * Implicit operator: adjacent terms without explicit operator → OR (Solr default).
 * Default field: bare values (no field: prefix) → FieldNode with field = df.
 *
 * Requirements: 2.1–2.11, 9.2, 13.2
 */

import type {
  ASTNode,
  BoolNode,
  FieldNode,
  PhraseNode,
  RangeNode,
  MatchAllNode,
  BoostNode,
  GroupNode,
} from '../ast/nodes';
import type { Token, TokenType } from '../lexer/lexer';

export interface ParseResult {
  ast: ASTNode;
  errors: ParseError[];
}

export interface ParseError {
  message: string;
  token: Token;
  position: number;
}


/** Parse a Lucene-syntax token stream into an AST. */
export function parseLucene(tokens: Token[], df: string): ParseResult {
  const errors: ParseError[] = [];
  let pos = 0;

  function peek(): Token {
    return tokens[pos] ?? tokens[tokens.length - 1]; // fallback to EOF
  }

  function advance(): Token {
    const token = tokens[pos];
    if (pos < tokens.length - 1) pos++; // don't advance past EOF
    return token;
  }

  function expect(type: TokenType): Token {
    const token = peek();
    if (token.type !== type) {
      errors.push({
        message: `Expected ${type} but found ${token.type} ('${token.value}')`,
        token,
        position: token.position,
      });
      return token;
    }
    return advance();
  }

  function isAtEnd(): boolean {
    return peek().type === 'EOF';
  }

  /** Check if the current token can start a primary expression. */
  function canStartPrimary(): boolean {
    const t = peek().type;
    return (
      t === 'LPAREN' ||
      t === 'FIELD' ||
      t === 'VALUE' ||
      t === 'PHRASE' ||
      t === 'WILDCARD' ||
      t === 'INCLUSIVE_RANGE_START' ||
      t === 'EXCLUSIVE_RANGE_START'
    );
  }

  // ─── Grammar rules ────────────────────────────────────────────────────────

  function parseQuery(): ASTNode {
    return parseOrExpr();
  }

  function parseOrExpr(): ASTNode {
    let left = parseAndExpr();

    // Explicit OR: consume OR tokens between sub-expressions
    while (peek().type === 'OR') {
      advance(); // consume OR
      const right = parseAndExpr();
      left = mergeBool('should', left, right);
    }

    // Implicit OR (Solr default behavior): when two terms appear adjacent
    // without an explicit operator, they are joined with OR.
    // Example: "java python" → BoolNode(should: [java, python])
    // This matches Solr's default q.op=OR behavior.
    while (!isAtEnd() && peek().type !== 'RPAREN' && peek().type !== 'OR' && peek().type !== 'AND' && canStartPrimary()) {
      const right = parseAndExpr();
      left = mergeBool('should', left, right);
    }

    return left;
  }

  function parseAndExpr(): ASTNode {
    let left = parseUnaryExpr();

    while (peek().type === 'AND') {
      advance(); // consume AND
      const right = parseUnaryExpr();
      left = mergeBool('must', left, right);
    }

    return left;
  }

  function parseUnaryExpr(): ASTNode {
    if (peek().type === 'NOT') {
      advance(); // consume NOT
      const child = parseUnaryExpr();
      const node: BoolNode = {
        type: 'bool',
        must: [],
        should: [],
        must_not: [child],
      };
      return node;
    }
    return parsePrimary();
  }

  function parsePrimary(): ASTNode {
    let node: ASTNode;
    const token = peek();

    switch (token.type) {
      case 'LPAREN':
        node = parseGroup();
        break;

      case 'FIELD':
        node = parseFieldExpr();
        break;

      case 'PHRASE':
        node = parsePhrase(df);
        break;

      case 'WILDCARD':
        node = parseWildcardOrMatchAll();
        break;

      case 'VALUE':
        node = parseValue();
        break;

      case 'INCLUSIVE_RANGE_START':
      case 'EXCLUSIVE_RANGE_START':
        node = parseRangeExpr(df);
        break;

      default:
        errors.push({
          message: `Unexpected token ${token.type} ('${token.value}')`,
          token,
          position: token.position,
        });
        advance(); // skip the bad token
        // Return a placeholder FieldNode to keep parsing
        node = { type: 'field', field: df, value: token.value || '?' } as FieldNode;
        break;
    }

    // Check for trailing BOOST
    if (peek().type === 'BOOST') {
      const boostToken = advance();
      const boostValue = parseFloat(boostToken.value);
      const boostNode: BoostNode = {
        type: 'boost',
        child: node,
        value: isNaN(boostValue) ? 1.0 : boostValue,
      };
      return boostNode;
    }

    return node;
  }

  function parseGroup(): ASTNode {
    advance(); // consume LPAREN
    const child = parseQuery();
    expect('RPAREN');
    const node: GroupNode = { type: 'group', child };
    return node;
  }

  function parseFieldExpr(): ASTNode {
    const fieldToken = advance(); // consume FIELD
    expect('COLON'); // consume COLON

    const next = peek();

    // field:"phrase"
    if (next.type === 'PHRASE') {
      return parsePhrase(fieldToken.value);
    }

    // field:[low TO high] or field:{low TO high}
    if (next.type === 'INCLUSIVE_RANGE_START' || next.type === 'EXCLUSIVE_RANGE_START') {
      return parseRangeExpr(fieldToken.value);
    }

    // field:value or field:*
    if (next.type === 'VALUE' || next.type === 'WILDCARD') {
      const valToken = advance();
      const node: FieldNode = {
        type: 'field',
        field: fieldToken.value,
        value: valToken.value,
      };
      return node;
    }

    // Unexpected token after field:
    errors.push({
      message: `Expected value, phrase, or range after '${fieldToken.value}:' but found ${next.type} ('${next.value}')`,
      token: next,
      position: next.position,
    });
    const fallback: FieldNode = {
      type: 'field',
      field: fieldToken.value,
      value: '',
    };
    return fallback;
  }

  function parsePhrase(field: string): ASTNode {
    const phraseToken = advance(); // consume PHRASE
    const node: PhraseNode = {
      type: 'phrase',
      text: phraseToken.value,
      field,
    };
    return node;
  }

  function parseRangeExpr(field: string): ASTNode {
    const startToken = advance(); // consume RANGE_START
    const lowerInclusive = startToken.type === 'INCLUSIVE_RANGE_START';

    const lowerToken = expect('VALUE');
    expect('TO');
    const upperToken = expect('VALUE');

    // Expect the matching range end
    const endType = lowerInclusive ? 'INCLUSIVE_RANGE_END' : 'EXCLUSIVE_RANGE_END';
    // Accept either inclusive or exclusive end bracket
    const endToken = peek();
    let upperInclusive: boolean;
    if (endToken.type === 'INCLUSIVE_RANGE_END') {
      upperInclusive = true;
      advance();
    } else if (endToken.type === 'EXCLUSIVE_RANGE_END') {
      upperInclusive = false;
      advance();
    } else {
      upperInclusive = lowerInclusive;
      errors.push({
        message: `Expected ${endType} but found ${endToken.type} ('${endToken.value}')`,
        token: endToken,
        position: endToken.position,
      });
    }

    const node: RangeNode = {
      type: 'range',
      field,
      lower: lowerToken.value,
      upper: upperToken.value,
      lowerInclusive,
      upperInclusive,
    };
    return node;
  }

  function parseWildcardOrMatchAll(): ASTNode {
    // Check for *:* pattern (MatchAll)
    if (
      pos + 2 < tokens.length &&
      tokens[pos + 1]?.type === 'COLON' &&
      tokens[pos + 2]?.type === 'WILDCARD'
    ) {
      advance(); // consume first WILDCARD
      advance(); // consume COLON
      advance(); // consume second WILDCARD
      const node: MatchAllNode = { type: 'matchAll' };
      return node;
    }

    // Standalone wildcard → treat as value with default field
    const wildcardToken = advance();
    const node: FieldNode = {
      type: 'field',
      field: df,
      value: wildcardToken.value,
    };
    return node;
  }

  function parseValue(): ASTNode {
    const valToken = advance(); // consume VALUE
    const node: FieldNode = {
      type: 'field',
      field: df,
      value: valToken.value,
    };
    return node;
  }

  // ─── Helpers ──────────────────────────────────────────────────────────────

  /**
   * Merge two nodes into a BoolNode under the given clause.
   *
   * Flattening optimization: if the left node is already a BoolNode with only
   * the same clause populated (e.g., a chain of ORs), we extend it rather than
   * nesting. This produces `should: [A, B, C]` instead of
   * `should: [should: [A, B], C]` for "A OR B OR C".
   */
  function mergeBool(clause: 'must' | 'should' | 'must_not', left: ASTNode, right: ASTNode): BoolNode {
    // If left is already a BoolNode with only the same clause populated, extend it
    if (
      left.type === 'bool' &&
      left[clause].length > 0 &&
      otherClauses(left, clause).every((arr) => arr.length === 0)
    ) {
      return {
        ...left,
        [clause]: [...left[clause], right],
      };
    }

    const node: BoolNode = {
      type: 'bool',
      must: [],
      should: [],
      must_not: [],
    };
    node[clause] = [left, right];
    return node;
  }

  function otherClauses(node: BoolNode, clause: 'must' | 'should' | 'must_not'): ASTNode[][] {
    const all: (keyof Pick<BoolNode, 'must' | 'should' | 'must_not'>)[] = ['must', 'should', 'must_not'];
    return all.filter((c) => c !== clause).map((c) => node[c]);
  }

  // ─── Entry point ──────────────────────────────────────────────────────────

  if (isAtEnd()) {
    // Empty token stream → match all
    return { ast: { type: 'matchAll' } as MatchAllNode, errors };
  }

  const ast = parseQuery();
  return { ast, errors };
}
