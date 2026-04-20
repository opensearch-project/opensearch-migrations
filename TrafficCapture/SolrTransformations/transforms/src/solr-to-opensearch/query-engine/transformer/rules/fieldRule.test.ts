import { describe, it, expect, vi } from 'vitest';
import { fieldRule } from './fieldRule';
import type { FieldNode } from '../../ast/nodes';

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
    expect(result).toEqual(new Map([['exists', new Map([['field', 'title']])]]));
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
  it('transforms fuzzy search without distance', () => {
    const node: FieldNode = { type: 'field', field: 'title', value: 'roam~' };
    const result = fieldRule(node, stubTransformChild);
    expect(result).toEqual(
      new Map([['fuzzy', new Map([['title', new Map([['value', 'roam']])]])]]),
    );
  });

  it('transforms fuzzy search with distance 2', () => {
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

  it('clamps fuzziness above 2 to max of 2', () => {
    const node: FieldNode = { type: 'field', field: 'title', value: 'roam~9' };
    const result = fieldRule(node, stubTransformChild);
    expect(result).toEqual(
      new Map([['fuzzy', new Map([['title', new Map([['value', 'roam'], ['fuzziness', 2]])]])]]),
    );
  });

  // --- Non-fuzzy tilde patterns ---
  it.each([
    { value: 'test~ing', desc: 'tilde in middle' },
    { value: '~test', desc: 'tilde at start' },
    { value: 'a~b~c', desc: 'multiple tildes' },
  ])('does not treat as fuzzy: $desc ($value)', ({ value }) => {
    const node: FieldNode = { type: 'field', field: 'title', value };
    const result = fieldRule(node, stubTransformChild);
    const queryType = result.keys().next().value;
    expect(queryType).not.toBe('fuzzy');
  });

  // --- Boost compatibility ---
  it('wildcard output uses expanded form for boost compatibility', () => {
    const node: FieldNode = { type: 'field', field: 'title', value: 'test*' };
    const result = fieldRule(node, stubTransformChild);
    const wildcardMap = result.get('wildcard') as Map<string, any>;
    expect(wildcardMap.get('title')).toBeInstanceOf(Map);
  });

  it('fuzzy output uses expanded form for boost compatibility', () => {
    const node: FieldNode = { type: 'field', field: 'title', value: 'roam~' };
    const result = fieldRule(node, stubTransformChild);
    const fuzzyMap = result.get('fuzzy') as Map<string, any>;
    expect(fuzzyMap.get('title')).toBeInstanceOf(Map);
  });

  // --- Leaf node behavior ---
  it('never recurses into children', () => {
    const node: FieldNode = { type: 'field', field: 'title', value: 'java' };
    const spy = vi.fn();
    fieldRule(node, spy);
    expect(spy).not.toHaveBeenCalled();
  });

  // --- Field metadata (fieldMappings) ---
  it('uses term query for keyword fields', () => {
    const node: FieldNode = { type: 'field', field: 'category', value: 'electronics' };
    const result = fieldRule(node, stubTransformChild, new Map([['category', 'keyword']]));
    expect(result).toEqual(
      new Map([['term', new Map([['category', new Map([['value', 'electronics']])]])]]),
    );
  });

  it('uses match query for text fields', () => {
    const node: FieldNode = { type: 'field', field: 'title', value: 'java' };
    const result = fieldRule(node, stubTransformChild, new Map([['title', 'text']]));
    expect(result).toEqual(
      new Map([['match', new Map([['title', new Map([['query', 'java']])]])]]),
    );
  });

  it('uses term query for integer fields', () => {
    const node: FieldNode = { type: 'field', field: 'price', value: '100' };
    const result = fieldRule(node, stubTransformChild, new Map([['price', 'integer']]));
    expect(result).toEqual(
      new Map([['term', new Map([['price', new Map([['value', '100']])]])]]),
    );
  });

  it('uses term query for date fields', () => {
    const node: FieldNode = { type: 'field', field: 'created', value: '2025-01-01' };
    const result = fieldRule(node, stubTransformChild, new Map([['created', 'date']]));
    expect(result).toEqual(
      new Map([['term', new Map([['created', new Map([['value', '2025-01-01']])]])]]),
    );
  });

  it('falls back to match when field not in mappings', () => {
    const node: FieldNode = { type: 'field', field: 'unknown', value: 'java' };
    const result = fieldRule(node, stubTransformChild, new Map([['title', 'text']]));
    expect(result).toEqual(
      new Map([['match', new Map([['unknown', new Map([['query', 'java']])]])]]),
    );
  });

  it('falls back to match when fieldMappings is undefined', () => {
    const node: FieldNode = { type: 'field', field: 'title', value: 'java' };
    const result = fieldRule(node, stubTransformChild, undefined);
    expect(result).toEqual(
      new Map([['match', new Map([['title', new Map([['query', 'java']])]])]]),
    );
  });

  it('term query uses expanded form for boost compatibility', () => {
    const node: FieldNode = { type: 'field', field: 'category', value: 'electronics' };
    const result = fieldRule(node, stubTransformChild, new Map([['category', 'keyword']]));
    const termMap = result.get('term') as Map<string, any>;
    expect(termMap.get('category')).toBeInstanceOf(Map);
  });
});
