import { describe, it, expect, vi } from 'vitest';
import { phraseRule } from './phraseRule';
import type { PhraseNode } from '../../ast/nodes';

/** Stub transformChild — not used by phraseRule but required by signature. */
const stubTransformChild = () => new Map();

describe('phraseRule', () => {
  const phraseTestCases: Array<{ field: string; text: string; description: string }> = [
    { field: 'title', text: 'hello world', description: 'basic phrase' },
    { field: 'description', text: 'search engine', description: 'different field' },
    { field: 'content', text: '', description: 'empty phrase' },
    { field: 'body', text: 'foo:bar baz', description: 'colon in phrase' },
  ];

  it.each(phraseTestCases)(
    'transforms $description: $field:"$text" → match_phrase',
    ({ field, text }) => {
      const node: PhraseNode = {
        type: 'phrase',
        field,
        text,
      };

      const result = phraseRule(node, stubTransformChild);

      expect(result).toEqual(
        new Map([['match_phrase', new Map([[field, new Map([['query', text]])]])]]),
      );
    },
  );

  it('never recurses into children because phrase is a leaf node', () => {
    const node: PhraseNode = {
      type: 'phrase',
      field: 'title',
      text: 'hello world',
    };
    const spy = vi.fn();

    phraseRule(node, spy);

    expect(spy).not.toHaveBeenCalled();
  });
});
