import { describe, it, expect } from 'vitest';
import { boolRule } from './boolRule';
import type { ASTNode, BoolNode, FieldNode } from '../../ast/nodes';
import type { TransformChild } from '../types';

/** Stub transformChild that wraps each node in a Map with its type as key. */
const stubTransformChild: TransformChild = (child: ASTNode): Map<string, any> =>
  new Map([[child.type, child]]);

const titleJava: FieldNode = { type: 'field', field: 'title', value: 'java' };
const authorSmith: FieldNode = { type: 'field', field: 'author', value: 'smith' };
const statusDraft: FieldNode = { type: 'field', field: 'status', value: 'draft' };

/** Extract the inner bool Map and verify only the expected keys are present. */
function expectBoolKeys(result: Map<string, any>, expectedKeys: string[]): Map<string, any> {
  const boolMap = result.get('bool') as Map<string, any>;
  expect(boolMap).toBeDefined();
  expect(boolMap.size).toBe(expectedKeys.length);
  expect([...boolMap.keys()]).toEqual(expect.arrayContaining(expectedKeys));
  return boolMap;
}

describe('boolRule', () => {
  it('transforms AND clauses to must', () => {
    const node: BoolNode = { type: 'bool', and: [titleJava, authorSmith], or: [], not: [] };
    const boolMap = expectBoolKeys(boolRule(node, stubTransformChild), ['must']);
    expect(boolMap.get('must')).toHaveLength(2);
  });

  it('transforms OR clauses to should', () => {
    const node: BoolNode = { type: 'bool', and: [], or: [titleJava, authorSmith], not: [] };
    const boolMap = expectBoolKeys(boolRule(node, stubTransformChild), ['should']);
    expect(boolMap.get('should')).toHaveLength(2);
  });

  it('transforms NOT clauses to must_not', () => {
    const node: BoolNode = { type: 'bool', and: [], or: [], not: [statusDraft] };
    const boolMap = expectBoolKeys(boolRule(node, stubTransformChild), ['must_not']);
    expect(boolMap.get('must_not')).toHaveLength(1);
  });

  it('transforms all three clause types together', () => {
    const node: BoolNode = { type: 'bool', and: [titleJava], or: [authorSmith], not: [statusDraft] };
    const boolMap = expectBoolKeys(boolRule(node, stubTransformChild), ['must', 'should', 'must_not']);
    expect(boolMap.get('must')).toHaveLength(1);
    expect(boolMap.get('should')).toHaveLength(1);
    expect(boolMap.get('must_not')).toHaveLength(1);
  });

  it('omits all clauses when all arrays are empty', () => {
    const node: BoolNode = { type: 'bool', and: [], or: [], not: [] };
    expectBoolKeys(boolRule(node, stubTransformChild), []);
  });

  it('calls transformChild for each child node', () => {
    const calls: ASTNode[] = [];
    const trackingTransformChild: TransformChild = (child) => {
      calls.push(child);
      return new Map([[child.type, child]]);
    };

    const node: BoolNode = { type: 'bool', and: [titleJava], or: [authorSmith], not: [statusDraft] };
    boolRule(node, trackingTransformChild);

    expect(calls).toEqual([titleJava, authorSmith, statusDraft]);
  });

  it('passes transformChild output directly into clause arrays', () => {
    const node: BoolNode = { type: 'bool', and: [titleJava], or: [], not: [] };
    const result = boolRule(node, stubTransformChild);

    const mustArray = (result.get('bool') as Map<string, any>).get('must');
    expect(mustArray[0]).toEqual(new Map([['field', titleJava]]));
  });

  it('throws when called with wrong node type', () => {
    const wrongNode: FieldNode = { type: 'field', field: 'title', value: 'java' };
    expect(() => boolRule(wrongNode, stubTransformChild)).toThrow(
      'boolRule called with wrong node type: field',
    );
  });
});
