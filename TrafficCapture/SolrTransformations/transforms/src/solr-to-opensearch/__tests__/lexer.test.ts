/**
 * Lexer tests — unit tests and property-based tests.
 *
 * Property tests use fast-check to validate correctness properties
 * from the design document.
 */
import { describe, it, expect } from 'vitest';
import * as fc from 'fast-check';
import { tokenize, type Token, type TokenType } from '../lexer/lexer';

// ─── Generators ───────────────────────────────────────────────────────────────

/** Alphanumeric field/value names (no special chars). */
const arbFieldName = () =>
  fc.stringMatching(/^[a-z][a-z0-9_]{0,11}$/).filter(
    (s) => s.length > 0 && !/^(AND|OR|NOT|TO)$/.test(s),
  );

/** Alphanumeric values. */
const arbValue = () =>
  fc.stringMatching(/^[a-z][a-z0-9]{0,11}$/).filter(
    (s) => s.length > 0 && !/^(AND|OR|NOT|TO)$/.test(s),
  );

/** Phrase text — no double quotes inside. */
const arbPhraseText = () =>
  fc.stringMatching(/^[a-zA-Z0-9 ]{1,20}$/).filter((s) => s.length > 0);

/** Whitespace of varying length. */
const arbWhitespace = () => fc.constantFrom(' ', '  ', '   ', '\t', ' \t ');

// ─── Unit Tests ───────────────────────────────────────────────────────────────

describe('Lexer', () => {
  describe('unit tests', () => {
    it('tokenizes *:* as WILDCARD COLON WILDCARD', () => {
      const result = tokenize('*:*');
      const types = result.tokens.map((t) => t.type);
      expect(types).toEqual(['WILDCARD', 'COLON', 'WILDCARD', 'EOF']);
    });

    it('tokenizes field:value as FIELD COLON VALUE', () => {
      const result = tokenize('title:java');
      const types = result.tokens.map((t) => t.type);
      expect(types).toEqual(['FIELD', 'COLON', 'VALUE', 'EOF']);
      expect(result.tokens[0].value).toBe('title');
      expect(result.tokens[2].value).toBe('java');
    });

    it('tokenizes boolean operators', () => {
      const result = tokenize('a AND b OR c NOT d');
      const types = result.tokens.map((t) => t.type);
      expect(types).toEqual(['VALUE', 'AND', 'VALUE', 'OR', 'VALUE', 'NOT', 'VALUE', 'EOF']);
    });

    it('tokenizes phrases', () => {
      const result = tokenize('"hello world"');
      expect(result.tokens[0].type).toBe('PHRASE');
      expect(result.tokens[0].value).toBe('hello world');
    });

    it('tokenizes range expressions', () => {
      const result = tokenize('price:[10 TO 100]');
      const types = result.tokens.map((t) => t.type);
      expect(types).toEqual([
        'FIELD', 'COLON', 'INCLUSIVE_RANGE_START', 'VALUE', 'TO', 'VALUE',
        'INCLUSIVE_RANGE_END', 'EOF',
      ]);
    });

    it('tokenizes exclusive range expressions', () => {
      const result = tokenize('price:{10 TO 100}');
      const types = result.tokens.map((t) => t.type);
      expect(types).toEqual([
        'FIELD', 'COLON', 'EXCLUSIVE_RANGE_START', 'VALUE', 'TO', 'VALUE',
        'EXCLUSIVE_RANGE_END', 'EOF',
      ]);
    });

    it('tokenizes boost', () => {
      const result = tokenize('title:java^2');
      const boostToken = result.tokens.find((t) => t.type === 'BOOST');
      expect(boostToken).toBeDefined();
      expect(boostToken!.value).toBe('2');
    });

    it('tokenizes parentheses', () => {
      const result = tokenize('(a OR b)');
      const types = result.tokens.map((t) => t.type);
      expect(types).toEqual(['LPAREN', 'VALUE', 'OR', 'VALUE', 'RPAREN', 'EOF']);
    });

    it('reports error for unterminated quote', () => {
      const result = tokenize('"hello world');
      expect(result.errors.length).toBeGreaterThanOrEqual(1);
      expect(result.errors[0].message).toContain('Unterminated quote');
      expect(result.errors[0].position).toBe(0);
    });

    it('reports error for unrecognized character', () => {
      const result = tokenize('title~java');
      expect(result.errors.length).toBeGreaterThanOrEqual(1);
      expect(result.errors[0].message).toContain('Unrecognized character');
    });

    it('handles empty query', () => {
      const result = tokenize('');
      expect(result.tokens).toEqual([{ type: 'EOF', value: '', position: 0 }]);
      expect(result.errors).toEqual([]);
    });

    it('tokenizes complex query: title:java AND price:[10 TO 100]', () => {
      const result = tokenize('title:java AND price:[10 TO 100]');
      const types = result.tokens.map((t) => t.type);
      expect(types).toEqual([
        'FIELD', 'COLON', 'VALUE', 'AND',
        'FIELD', 'COLON', 'INCLUSIVE_RANGE_START', 'VALUE', 'TO', 'VALUE',
        'INCLUSIVE_RANGE_END', 'EOF',
      ]);
    });
  });

  // ─── Property Tests ─────────────────────────────────────────────────────────

  // Feature: solr-query-parser, Property 2: Lexer produces no whitespace tokens
  // **Validates: Requirements 1.3**
  describe('Property 2: Lexer produces no whitespace tokens', () => {
    it('never emits whitespace tokens and all positions are non-negative', () => {
      fc.assert(
        fc.property(
          // Generate Solr-like query strings with varying whitespace
          fc.array(
            fc.oneof(
              // field:value with whitespace padding
              fc.tuple(arbFieldName(), arbValue(), arbWhitespace()).map(
                ([f, v, ws]) => `${ws}${f}:${v}${ws}`,
              ),
              // bare value with whitespace
              fc.tuple(arbValue(), arbWhitespace()).map(([v, ws]) => `${ws}${v}${ws}`),
              // phrase with whitespace
              fc.tuple(arbPhraseText(), arbWhitespace()).map(
                ([t, ws]) => `${ws}"${t}"${ws}`,
              ),
              // operators with whitespace
              fc.tuple(
                fc.constantFrom('AND', 'OR', 'NOT'),
                arbWhitespace(),
              ).map(([op, ws]) => `${ws}${op}${ws}`),
            ),
            { minLength: 1, maxLength: 5 },
          ).map((parts) => parts.join('')),
          (query) => {
            const result = tokenize(query);
            for (const token of result.tokens) {
              // No whitespace-type tokens
              expect(token.type).not.toBe('WHITESPACE');
              // All positions are non-negative
              expect(token.position).toBeGreaterThanOrEqual(0);
            }
          },
        ),
        { numRuns: 100 },
      );
    });
  });

  // Feature: solr-query-parser, Property 3: Phrase tokenization preserves content
  // **Validates: Requirements 1.4**
  describe('Property 3: Phrase tokenization preserves content', () => {
    it('PHRASE token value equals text between quotes', () => {
      fc.assert(
        fc.property(
          // Generate a query with a double-quoted phrase
          fc.tuple(
            arbPhraseText(),
            fc.constantFrom('', 'title:', 'content:'),
          ).map(([text, prefix]) => ({ text, query: `${prefix}"${text}"` })),
          ({ text, query }) => {
            const result = tokenize(query);
            const phraseTokens = result.tokens.filter((t) => t.type === 'PHRASE');
            expect(phraseTokens.length).toBe(1);
            expect(phraseTokens[0].value).toBe(text);
          },
        ),
        { numRuns: 100 },
      );
    });
  });

  // Feature: solr-query-parser, Property 4: Lexer error on unterminated quotes and unrecognized characters
  // **Validates: Requirements 1.6, 9.1**
  describe('Property 4: Lexer error on unterminated quotes and unrecognized characters', () => {
    it('produces at least one error for unterminated quotes', () => {
      fc.assert(
        fc.property(
          // Generate query strings with an unterminated quote
          fc.tuple(
            fc.constantFrom('', 'title:', 'a AND '),
            arbPhraseText(),
          ).map(([prefix, text]) => `${prefix}"${text}`),
          (query) => {
            const result = tokenize(query);
            expect(result.errors.length).toBeGreaterThanOrEqual(1);
            // At least one error should mention the position
            const hasPositionedError = result.errors.some(
              (e) => typeof e.position === 'number' && e.position >= 0 && e.message.length > 0,
            );
            expect(hasPositionedError).toBe(true);
          },
        ),
        { numRuns: 100 },
      );
    });

    it('produces at least one error for unrecognized characters', () => {
      fc.assert(
        fc.property(
          // Generate query strings with unrecognized characters
          fc.tuple(
            fc.constantFrom('', 'title:java '),
            fc.constantFrom('~', '!', '@', '#', '$', '%', '&', '=', '\\', '|', ';'),
          ).map(([prefix, badChar]) => `${prefix}${badChar}`),
          (query) => {
            const result = tokenize(query);
            expect(result.errors.length).toBeGreaterThanOrEqual(1);
            const hasPositionedError = result.errors.some(
              (e) => typeof e.position === 'number' && e.position >= 0 && e.message.length > 0,
            );
            expect(hasPositionedError).toBe(true);
          },
        ),
        { numRuns: 100 },
      );
    });
  });
});
