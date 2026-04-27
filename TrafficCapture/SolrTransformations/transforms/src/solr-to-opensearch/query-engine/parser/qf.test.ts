import { describe, it, expect } from 'vitest';
import { parseFieldSpecs, applyQueryFields } from './qf';
import type { ASTNode, BareNode } from '../ast/nodes';

const p = (...entries: [string, string][]) => new Map(entries);

// ─── parseFieldSpecs ──────────────────────────────────────────────────────────

describe('parseFieldSpecs', () => {
  it('returns undefined for undefined input', () => {
    expect(parseFieldSpecs(undefined)).toBeUndefined();
  });

  it('returns undefined for empty string', () => {
    expect(parseFieldSpecs('')).toBeUndefined();
  });

  it('returns undefined for whitespace-only string', () => {
    expect(parseFieldSpecs('   ')).toBeUndefined();
  });

  it('parses a single field without boost', () => {
    expect(parseFieldSpecs('title')).toEqual(['title']);
  });

  it('passes through field^boost token as-is', () => {
    expect(parseFieldSpecs('title^2')).toEqual(['title^2']);
  });

  it('passes through decimal boost as-is', () => {
    expect(parseFieldSpecs('title^0.5')).toEqual(['title^0.5']);
  });

  it('splits multiple fields', () => {
    expect(parseFieldSpecs('title^2 body description^0.5')).toEqual([
      'title^2', 'body', 'description^0.5',
    ]);
  });

  it('passes through all fields with boost values', () => {
    expect(parseFieldSpecs('title^2 body^1')).toEqual(['title^2', 'body^1']);
  });

  it('handles extra whitespace between fields', () => {
    expect(parseFieldSpecs('title^2   body')).toEqual(['title^2', 'body']);
  });

  it('handles dotted field names', () => {
    expect(parseFieldSpecs('metadata.title^3')).toEqual(['metadata.title^3']);
  });
});

// ─── applyQueryFields ─────────────────────────────────────────────────────────

describe('applyQueryFields', () => {
  it('stamps queryFields onto a BareNode', () => {
    const node: BareNode = { type: 'bare', value: 'java', isPhrase: false };
    applyQueryFields(node, p(['qf', 'title^2 body']));
    expect(node.queryFields).toEqual(['title^2', 'body']);
  });

  it('clears defaultField when stamping queryFields', () => {
    const node: BareNode = { type: 'bare', value: 'java', isPhrase: false, defaultField: 'content' };
    applyQueryFields(node, p(['qf', 'title body']));
    expect(node.queryFields).toEqual(['title', 'body']);
    expect(node.defaultField).toBeUndefined();
  });

  it('stamps tieBreaker when tie param is set', () => {
    const node: BareNode = { type: 'bare', value: 'java', isPhrase: false };
    applyQueryFields(node, p(['qf', 'title body'], ['tie', '0.1']));
    expect(node.tieBreaker).toBe(0.1);
  });

  it('does not stamp tieBreaker when tie is absent', () => {
    const node: BareNode = { type: 'bare', value: 'java', isPhrase: false };
    applyQueryFields(node, p(['qf', 'title body']));
    expect(node.tieBreaker).toBeUndefined();
  });

  it('does not stamp tieBreaker when tie is NaN', () => {
    const node: BareNode = { type: 'bare', value: 'java', isPhrase: false };
    applyQueryFields(node, p(['qf', 'title body'], ['tie', 'abc']));
    expect(node.tieBreaker).toBeUndefined();
  });

  it('does nothing when qf is absent', () => {
    const node: BareNode = { type: 'bare', value: 'java', isPhrase: false };
    applyQueryFields(node, p());
    expect(node.queryFields).toBeUndefined();
  });

  it('stamps queryFields onto all BareNodes in a BoolNode', () => {
    const a: BareNode = { type: 'bare', value: 'java', isPhrase: false };
    const b: BareNode = { type: 'bare', value: 'python', isPhrase: false };
    const node: ASTNode = { type: 'bool', and: [a], or: [b], not: [] };
    applyQueryFields(node, p(['qf', 'title body']));
    expect(a.queryFields).toEqual(['title', 'body']);
    expect(b.queryFields).toEqual(['title', 'body']);
  });

  it('recurses into boost node child', () => {
    const bare: BareNode = { type: 'bare', value: 'java', isPhrase: false };
    const node: ASTNode = { type: 'boost', child: bare, value: 2 };
    applyQueryFields(node, p(['qf', 'title']));
    expect(bare.queryFields).toEqual(['title']);
  });

  it('recurses into group node child', () => {
    const bare: BareNode = { type: 'bare', value: 'java', isPhrase: false };
    const node: ASTNode = { type: 'group', child: bare };
    applyQueryFields(node, p(['qf', 'title']));
    expect(bare.queryFields).toEqual(['title']);
  });

  it('does not touch FieldNode', () => {
    const node: ASTNode = { type: 'field', field: 'title', value: 'java' };
    applyQueryFields(node, p(['qf', 'title']));
  });

  it('does not touch MatchAllNode', () => {
    const node: ASTNode = { type: 'matchAll' };
    applyQueryFields(node, p(['qf', 'title']));
  });
});
