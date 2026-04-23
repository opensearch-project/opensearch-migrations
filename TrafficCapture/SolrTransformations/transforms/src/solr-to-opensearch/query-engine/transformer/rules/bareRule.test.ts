import { describe, it, expect, vi } from 'vitest';
import { bareRule } from './bareRule';
import type { BareNode } from '../../ast/nodes';

/** Stub transformChild — not used by bareRule but required by signature. */
const stubTransformChild = () => new Map();

describe('bareRule', () => {
  // ─── query_string path (no queryFields) ───────────────────────────────────

  it('transforms bare term without defaultField to query_string', () => {
    const node: BareNode = { type: 'bare', value: 'java', isPhrase: false };
    expect(bareRule(node, stubTransformChild)).toEqual(
      new Map([['query_string', new Map([['query', 'java']])]]),
    );
  });

  it('transforms bare term with defaultField to query_string with default_field', () => {
    const node: BareNode = { type: 'bare', value: 'java', isPhrase: false, defaultField: 'content' };
    expect(bareRule(node, stubTransformChild)).toEqual(
      new Map([['query_string', new Map([['query', 'java'], ['default_field', 'content']])]]),
    );
  });

  it('transforms bare phrase without defaultField to query_string with quotes', () => {
    const node: BareNode = { type: 'bare', value: 'hello world', isPhrase: true };
    expect(bareRule(node, stubTransformChild)).toEqual(
      new Map([['query_string', new Map([['query', '"hello world"']])]]),
    );
  });

  it('transforms bare phrase with defaultField to query_string with quotes and default_field', () => {
    const node: BareNode = { type: 'bare', value: 'hello world', isPhrase: true, defaultField: 'title' };
    expect(bareRule(node, stubTransformChild)).toEqual(
      new Map([['query_string', new Map([['query', '"hello world"'], ['default_field', 'title']])]]),
    );
  });

  // ─── multi_match path (queryFields present) ───────────────────────────────

  it('emits multi_match best_fields for bare term with queryFields', () => {
    const node: BareNode = {
      type: 'bare',
      value: 'java',
      isPhrase: false,
      queryFields: ['title^2', 'body'],
    };
    expect(bareRule(node, stubTransformChild)).toEqual(
      new Map([['multi_match', new Map<string, any>([
        ['query', 'java'],
        ['fields', ['title^2', 'body']],
        ['type', 'best_fields'],
      ])]]),
    );
  });

  it('emits multi_match phrase for bare phrase with queryFields', () => {
    const node: BareNode = {
      type: 'bare',
      value: 'hello world',
      isPhrase: true,
      queryFields: ['title', 'body'],
    };
    expect(bareRule(node, stubTransformChild)).toEqual(
      new Map([['multi_match', new Map<string, any>([
        ['query', 'hello world'],
        ['fields', ['title', 'body']],
        ['type', 'phrase'],
      ])]]),
    );
  });

  it('passes field^boost tokens through as-is', () => {
    const node: BareNode = {
      type: 'bare',
      value: 'search',
      isPhrase: false,
      queryFields: ['title^2.5', 'description^0.5'],
    };
    const result = bareRule(node, stubTransformChild);
    const mm = result.get('multi_match') as Map<string, any>;
    expect(mm.get('fields')).toEqual(['title^2.5', 'description^0.5']);
  });

  it('includes tie_breaker in multi_match when tieBreaker is set', () => {
    const node: BareNode = {
      type: 'bare',
      value: 'java',
      isPhrase: false,
      queryFields: ['title^2', 'body'],
      tieBreaker: 0.3,
    };
    const result = bareRule(node, stubTransformChild);
    const mm = result.get('multi_match') as Map<string, any>;
    expect(mm.get('tie_breaker')).toBe(0.3);
  });

  it('omits tie_breaker when tieBreaker is not set', () => {
    const node: BareNode = {
      type: 'bare',
      value: 'java',
      isPhrase: false,
      queryFields: ['title', 'body'],
    };
    const result = bareRule(node, stubTransformChild);
    const mm = result.get('multi_match') as Map<string, any>;
    expect(mm.has('tie_breaker')).toBe(false);
  });

  it('includes tie_breaker=0 when tieBreaker is explicitly 0', () => {
    const node: BareNode = {
      type: 'bare',
      value: 'java',
      isPhrase: false,
      queryFields: ['title', 'body'],
      tieBreaker: 0,
    };
    const result = bareRule(node, stubTransformChild);
    const mm = result.get('multi_match') as Map<string, any>;
    expect(mm.get('tie_breaker')).toBe(0);
  });

  it('includes tie_breaker=1 for pure sum scoring', () => {
    const node: BareNode = {
      type: 'bare',
      value: 'java',
      isPhrase: false,
      queryFields: ['title', 'body'],
      tieBreaker: 1,
    };
    const result = bareRule(node, stubTransformChild);
    const mm = result.get('multi_match') as Map<string, any>;
    expect(mm.get('tie_breaker')).toBe(1);
  });

  it('phrase type with tieBreaker still uses type phrase', () => {
    const node: BareNode = {
      type: 'bare',
      value: 'hello world',
      isPhrase: true,
      queryFields: ['title', 'body'],
      tieBreaker: 0.1,
    };
    const result = bareRule(node, stubTransformChild);
    const mm = result.get('multi_match') as Map<string, any>;
    expect(mm.get('type')).toBe('phrase');
    expect(mm.get('tie_breaker')).toBe(0.1);
  });

  it('falls back to query_string when queryFields is empty array', () => {
    const node: BareNode = { type: 'bare', value: 'java', isPhrase: false, queryFields: [] };
    expect(bareRule(node, stubTransformChild)).toEqual(
      new Map([['query_string', new Map([['query', 'java']])]]),
    );
  });

  it('queryFields takes precedence over defaultField', () => {
    const node: BareNode = {
      type: 'bare',
      value: 'java',
      isPhrase: false,
      defaultField: 'content',
      queryFields: ['title'],
    };
    const result = bareRule(node, stubTransformChild);
    expect(result.has('multi_match')).toBe(true);
    expect(result.has('query_string')).toBe(false);
  });

  // ─── escaping (query_string path only) ────────────────────────────────────

  describe('escapes special characters in query_string path', () => {
    const escapeTestCases: Array<{ input: string; expected: string; description: string }> = [
      { input: 'foo:bar', expected: String.raw`foo\:bar`, description: 'colon (field separator)' },
      { input: 'a+b', expected: String.raw`a\+b`, description: 'plus (required term)' },
      { input: 'a-b', expected: String.raw`a\-b`, description: 'minus (excluded term)' },
      { input: 'wild*card', expected: String.raw`wild\*card`, description: 'asterisk (wildcard)' },
      { input: 'single?char', expected: String.raw`single\?char`, description: 'question mark' },
      { input: '(group)', expected: String.raw`\(group\)`, description: 'parentheses' },
      { input: '[range]', expected: String.raw`\[range\]`, description: 'square brackets' },
      { input: '{exclusive}', expected: String.raw`\{exclusive\}`, description: 'curly braces' },
      { input: 'boost^2', expected: String.raw`boost\^2`, description: 'caret (boost)' },
      { input: '"quoted"', expected: String.raw`\"quoted\"`, description: 'double quotes' },
      { input: 'fuzzy~2', expected: String.raw`fuzzy\~2`, description: 'tilde (fuzzy)' },
      { input: 'path/to', expected: String.raw`path\/to`, description: 'forward slash' },
      { input: String.raw`back\slash`, expected: String.raw`back\\slash`, description: 'backslash' },
      { input: 'a&&b', expected: String.raw`a\&\&b`, description: 'double ampersand (AND)' },
      { input: 'a||b', expected: String.raw`a\|\|b`, description: 'double pipe (OR)' },
      { input: 'a<b', expected: String.raw`a\<b`, description: 'less than' },
      { input: 'a>b', expected: String.raw`a\>b`, description: 'greater than' },
      { input: '!negated', expected: String.raw`\!negated`, description: 'exclamation (NOT)' },
      { input: 'a=b', expected: String.raw`a\=b`, description: 'equals sign' },
    ];

    it.each(escapeTestCases)('escapes $description: "$input" → "$expected"', ({ input, expected }) => {
      const node: BareNode = { type: 'bare', value: input, isPhrase: false };
      const result = bareRule(node, stubTransformChild);
      expect(result).toEqual(
        new Map([['query_string', new Map([['query', expected]])]]),
      );
    });
  });

  it('never recurses into children because bare is a leaf node', () => {
    const node: BareNode = { type: 'bare', value: 'test', isPhrase: false };
    const spy = vi.fn();
    bareRule(node, spy);
    expect(spy).not.toHaveBeenCalled();
  });
});
