import { describe, it, expect, vi } from 'vitest';
import { bareRule } from './bareRule';
import type { BareNode } from '../../ast/nodes';

/** Stub transformChild — not used by bareRule but required by signature. */
const stubTransformChild = () => new Map();

describe('bareRule', () => {
  it('transforms bare term without defaultField to query_string', () => {
    const node: BareNode = {
      type: 'bare',
      value: 'java',
      isPhrase: false,
    };

    const result = bareRule(node, stubTransformChild);

    expect(result).toEqual(
      new Map([['query_string', new Map([['query', 'java']])]]),
    );
  });

  it('transforms bare term with defaultField to query_string with default_field', () => {
    const node: BareNode = {
      type: 'bare',
      value: 'java',
      isPhrase: false,
      defaultField: 'content',
    };

    const result = bareRule(node, stubTransformChild);

    expect(result).toEqual(
      new Map([['query_string', new Map([['query', 'java'], ['default_field', 'content']])]]),
    );
  });

  it('transforms bare phrase without defaultField to query_string with quotes', () => {
    const node: BareNode = {
      type: 'bare',
      value: 'hello world',
      isPhrase: true,
    };

    const result = bareRule(node, stubTransformChild);

    expect(result).toEqual(
      new Map([['query_string', new Map([['query', '"hello world"']])]]),
    );
  });

  it('transforms bare phrase with defaultField to query_string with quotes and default_field', () => {
    const node: BareNode = {
      type: 'bare',
      value: 'hello world',
      isPhrase: true,
      defaultField: 'title',
    };

    const result = bareRule(node, stubTransformChild);

    expect(result).toEqual(
      new Map([['query_string', new Map([['query', '"hello world"'], ['default_field', 'title']])]]),
    );
  });

  describe('escapes special characters', () => {
    const escapeTestCases: Array<{ input: string; expected: string; description: string }> = [
      { input: 'foo:bar', expected: String.raw`foo\:bar`, description: 'colon (field separator)' },
      { input: 'a+b', expected: String.raw`a\+b`, description: 'plus (required term)' },
      { input: 'a-b', expected: String.raw`a\-b`, description: 'minus (excluded term)' },
      { input: 'wild*card', expected: String.raw`wild\*card`, description: 'asterisk (wildcard)' },
      { input: 'single?char', expected: String.raw`single\?char`, description: 'question mark (single char wildcard)' },
      { input: '(group)', expected: String.raw`\(group\)`, description: 'parentheses (grouping)' },
      { input: '[range]', expected: String.raw`\[range\]`, description: 'square brackets (range)' },
      { input: '{exclusive}', expected: String.raw`\{exclusive\}`, description: 'curly braces (exclusive range)' },
      { input: 'boost^2', expected: String.raw`boost\^2`, description: 'caret (boost)' },
      { input: '"quoted"', expected: String.raw`\"quoted\"`, description: 'double quotes (phrase)' },
      { input: 'fuzzy~2', expected: String.raw`fuzzy\~2`, description: 'tilde (fuzzy/proximity)' },
      { input: 'path/to', expected: String.raw`path\/to`, description: 'forward slash (regex delimiter)' },
      { input: String.raw`back\slash`, expected: String.raw`back\\slash`, description: 'backslash (escape char)' },
      { input: 'a&&b', expected: String.raw`a\&\&b`, description: 'double ampersand (AND)' },
      { input: 'a||b', expected: String.raw`a\|\|b`, description: 'double pipe (OR)' },
      { input: 'a<b', expected: String.raw`a\<b`, description: 'less than (range)' },
      { input: 'a>b', expected: String.raw`a\>b`, description: 'greater than (range)' },
      { input: '!negated', expected: String.raw`\!negated`, description: 'exclamation (NOT)' },
      { input: 'a=b', expected: String.raw`a\=b`, description: 'equals sign' },
    ];

    it.each(escapeTestCases)('escapes $description: "$input" → "$expected"', ({ input, expected }) => {
      const node: BareNode = {
        type: 'bare',
        value: input,
        isPhrase: false,
      };

      const result = bareRule(node, stubTransformChild);

      expect(result).toEqual(
        new Map([['query_string', new Map([['query', expected]])]]),
      );
    });

    it.each(escapeTestCases)('escapes $description in phrase: "$input"', ({ input, expected }) => {
      const node: BareNode = {
        type: 'bare',
        value: input,
        isPhrase: true,
      };

      const result = bareRule(node, stubTransformChild);

      expect(result).toEqual(
        new Map([['query_string', new Map([['query', `"${expected}"`]])]]),
      );
    });
  });

  it('never recurses into children because bare is a leaf node', () => {
    const node: BareNode = {
      type: 'bare',
      value: 'test',
      isPhrase: false,
    };
    const spy = vi.fn();

    bareRule(node, spy);

    expect(spy).not.toHaveBeenCalled();
  });
});
