import { describe, it, expect, vi } from 'vitest';
import { fieldRule } from './fieldRule';
import type { FieldNode } from '../../ast/nodes';

/** Stub transformChild — not used by fieldRule but required by signature. */
const stubTransformChild = () => new Map();

describe('fieldRule', () => {
  // --- Plain match queries ---

  it.each([
    { field: 'title', value: 'java', desc: 'simple field:value' },
    { field: 'author', value: 'smith', desc: 'simple field name' },
    { field: '_text_', value: 'search', desc: 'underscore field name' },
    { field: 'metadata.author', value: 'doe', desc: 'dotted field name' },
    { field: 'title', value: 'foo-bar_baz.v1@test#123', desc: 'multiple special chars' },
  ])('transforms $desc to match query', ({ field, value }) => {
    const node: FieldNode = { type: 'field', field, value };

    const result = fieldRule(node, stubTransformChild);

    expect(result).toEqual(
      new Map([['match', new Map([[field, new Map([['query', value]])]])]]),
    );
  });

  // --- Existence search ---

  it('transforms existence search field:* to exists query', () => {
    const node: FieldNode = { type: 'field', field: 'title', value: '*' };

    const result = fieldRule(node, stubTransformChild);

    expect(result).toEqual(
      new Map([['exists', new Map([['field', 'title']])]]),
    );
  });

  // --- Wildcard queries ---

  it.each([
    { value: 'te?t', desc: 'single char wildcard (?)' },
    { value: 'tes*ing', desc: '* in middle' },
    { value: 'test*', desc: 'trailing *' },
    { value: '**', desc: 'double *' },
    { value: 'a?b*c', desc: 'mixed wildcards' },
  ])('transforms wildcard pattern $desc to wildcard query', ({ value }) => {
    const node: FieldNode = { type: 'field', field: 'title', value };

    const result = fieldRule(node, stubTransformChild);

    expect(result).toEqual(
      new Map([['wildcard', new Map([['title', new Map([['value', value]])]])]]),
    );
  });

  // --- Fuzzy queries ---

  it('transforms fuzzy search without distance to fuzzy query', () => {
    const node: FieldNode = { type: 'field', field: 'title', value: 'roam~' };

    const result = fieldRule(node, stubTransformChild);

    expect(result).toEqual(
      new Map([['fuzzy', new Map([['title', new Map([['value', 'roam']])]])]]),
    );
  });

  it('transforms fuzzy search with distance to fuzzy query with fuzziness', () => {
    const node: FieldNode = { type: 'field', field: 'title', value: 'roam~2' };

    const result = fieldRule(node, stubTransformChild);

    expect(result).toEqual(
      new Map([['fuzzy', new Map([['title', new Map([['value', 'roam'], ['fuzziness', 2]])]])]]),
    );
  });

  it('transforms fuzzy search with distance 1', () => {
    const node: FieldNode = { type: 'field', field: 'title', value: 'roam~1' };

    const result = fieldRule(node, stubTransformChild);

    expect(result).toEqual(
      new Map([['fuzzy', new Map([['title', new Map([['value', 'roam'], ['fuzziness', 1]])]])]]),
    );
  });

  it('transforms fuzzy search with distance 0', () => {
    const node: FieldNode = { type: 'field', field: 'title', value: 'roam~0' };

    const result = fieldRule(node, stubTransformChild);

    expect(result).toEqual(
      new Map([['fuzzy', new Map([['title', new Map([['value', 'roam'], ['fuzziness', 0]])]])]]),
    );
  });

  // --- Non-fuzzy tilde patterns (should NOT be treated as fuzzy) ---

  it.each([
    { value: 'test~ing', desc: 'tilde in middle' },
    { value: '~test', desc: 'tilde at start' },
    { value: 'a~b~c', desc: 'multiple tildes' },
  ])('does not treat as fuzzy: $desc ($value)', ({ value }) => {
    const node: FieldNode = { type: 'field', field: 'title', value };

    const result = fieldRule(node, stubTransformChild);

    // These should produce match queries, not fuzzy
    const queryType = result.keys().next().value;
    expect(queryType).not.toBe('fuzzy');
  });

  // --- Boost compatibility ---

  it('wildcard output uses expanded form for boost compatibility', () => {
    const node: FieldNode = { type: 'field', field: 'title', value: 'test*' };
    const result = fieldRule(node, stubTransformChild);

    // Verify the structure: {"wildcard": {"title": Map{...}}}
    // The inner value must be a Map (not a string) so boostRule can inject boost
    const wildcardMap = result.get('wildcard') as Map<string, any>;
    const fieldParams = wildcardMap.get('title');
    expect(fieldParams).toBeInstanceOf(Map);
  });

  it('fuzzy output uses expanded form for boost compatibility', () => {
    const node: FieldNode = { type: 'field', field: 'title', value: 'roam~' };
    const result = fieldRule(node, stubTransformChild);

    const fuzzyMap = result.get('fuzzy') as Map<string, any>;
    const fieldParams = fuzzyMap.get('title');
    expect(fieldParams).toBeInstanceOf(Map);
  });

  // --- Leaf node behavior ---

  it('never recurses into children because field is a leaf node', () => {
    const node: FieldNode = { type: 'field', field: 'title', value: 'java' };
    const spy = vi.fn();

    fieldRule(node, spy);

    expect(spy).not.toHaveBeenCalled();
  });
});
