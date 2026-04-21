import { describe, it, expect } from 'vitest';
import { parseQueryFields, applyQueryFields } from './qf';
import type { ASTNode, BareNode } from '../ast/nodes';

// ─── parseQueryFields ─────────────────────────────────────────────────────────

describe('parseQueryFields', () => {
  it('returns undefined for undefined input', () => {
    expect(parseQueryFields(undefined)).toBeUndefined();
  });

  it('returns undefined for empty string', () => {
    expect(parseQueryFields('')).toBeUndefined();
  });

  it('returns undefined for whitespace-only string', () => {
    expect(parseQueryFields('   ')).toBeUndefined();
  });

  it('parses a single field without boost', () => {
    expect(parseQueryFields('title')).toEqual(['title']);
  });

  it('passes through field^boost token as-is', () => {
    expect(parseQueryFields('title^2')).toEqual(['title^2']);
  });

  it('passes through decimal boost as-is', () => {
    expect(parseQueryFields('title^0.5')).toEqual(['title^0.5']);
  });

  it('splits multiple fields', () => {
    expect(parseQueryFields('title^2 body description^0.5')).toEqual([
      'title^2', 'body', 'description^0.5',
    ]);
  });

  it('passes through all fields with boost values', () => {
    expect(parseQueryFields('title^2 body^1')).toEqual(['title^2', 'body^1']);
  });

  it('handles extra whitespace between fields', () => {
    expect(parseQueryFields('title^2   body')).toEqual(['title^2', 'body']);
  });

  it('handles dotted field names', () => {
    expect(parseQueryFields('metadata.title^3')).toEqual(['metadata.title^3']);
  });
});

// ─── applyQueryFields ─────────────────────────────────────────────────────────

describe('applyQueryFields', () => {
  const qf = ['title^2', 'body'];

  it('stamps queryFields onto a BareNode', () => {
    const node: BareNode = { type: 'bare', value: 'java', isPhrase: false };
    applyQueryFields(node, qf);
    expect(node.queryFields).toEqual(qf);
  });

  it('clears defaultField when stamping queryFields', () => {
    const node: BareNode = { type: 'bare', value: 'java', isPhrase: false, defaultField: 'content' };
    applyQueryFields(node, qf);
    expect(node.queryFields).toEqual(qf);
    expect(node.defaultField).toBeUndefined();
  });

  it('stamps queryFields onto all BareNodes in a BoolNode', () => {
    const a: BareNode = { type: 'bare', value: 'java', isPhrase: false };
    const b: BareNode = { type: 'bare', value: 'python', isPhrase: false };
    const node: ASTNode = { type: 'bool', and: [a], or: [b], not: [] };
    applyQueryFields(node, qf);
    expect(a.queryFields).toEqual(qf);
    expect(b.queryFields).toEqual(qf);
  });

  it('recurses into boost node child', () => {
    const bare: BareNode = { type: 'bare', value: 'java', isPhrase: false };
    const node: ASTNode = { type: 'boost', child: bare, value: 2 };
    applyQueryFields(node, qf);
    expect(bare.queryFields).toEqual(qf);
  });

  it('recurses into group node child', () => {
    const bare: BareNode = { type: 'bare', value: 'java', isPhrase: false };
    const node: ASTNode = { type: 'group', child: bare };
    applyQueryFields(node, qf);
    expect(bare.queryFields).toEqual(qf);
  });

  it('does not touch FieldNode', () => {
    const node: ASTNode = { type: 'field', field: 'title', value: 'java' };
    applyQueryFields(node, qf);
  });

  it('does not touch MatchAllNode', () => {
    const node: ASTNode = { type: 'matchAll' };
    applyQueryFields(node, qf);
  });
});
