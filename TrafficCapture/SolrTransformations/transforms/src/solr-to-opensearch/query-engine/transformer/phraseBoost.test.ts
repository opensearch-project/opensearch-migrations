import { describe, it, expect } from 'vitest';
import { buildPhraseBoost, applyPhraseBoost } from './phraseBoost';

const p = (...entries: [string, string][]) => new Map(entries);

// ─── buildPhraseBoost ─────────────────────────────────────────────────────────

describe('buildPhraseBoost', () => {
  it('returns null when pf is not set', () => {
    expect(buildPhraseBoost('foo bar', p())).toBeNull();
  });

  it('returns null when pf is empty', () => {
    expect(buildPhraseBoost('foo bar', p(['pf', '']))).toBeNull();
  });

  it('builds multi_match phrase with single field', () => {
    const result = buildPhraseBoost('foo bar', p(['pf', 'title^50']));
    const mm = result!.get('multi_match') as Map<string, any>;
    expect(mm.get('query')).toBe('foo bar');
    expect(mm.get('fields')).toEqual(['title^50']);
    expect(mm.get('type')).toBe('phrase');
    expect(mm.has('slop')).toBe(false);
  });

  it('builds multi_match phrase with multiple fields', () => {
    const result = buildPhraseBoost('foo bar', p(['pf', 'title^50 body^20']));
    const mm = result!.get('multi_match') as Map<string, any>;
    expect(mm.get('fields')).toEqual(['title^50', 'body^20']);
  });

  it('includes slop when ps > 0', () => {
    const result = buildPhraseBoost('foo bar', p(['pf', 'title^50'], ['ps', '10']));
    const mm = result!.get('multi_match') as Map<string, any>;
    expect(mm.get('slop')).toBe(10);
  });

  it('omits slop when ps=0', () => {
    const result = buildPhraseBoost('foo bar', p(['pf', 'title^50'], ['ps', '0']));
    const mm = result!.get('multi_match') as Map<string, any>;
    expect(mm.has('slop')).toBe(false);
  });

  it('omits slop when ps is absent', () => {
    const result = buildPhraseBoost('foo bar', p(['pf', 'title^50']));
    const mm = result!.get('multi_match') as Map<string, any>;
    expect(mm.has('slop')).toBe(false);
  });

  it('omits slop when ps is negative', () => {
    // Negative slop is meaningless — treated same as 0 (no slop)
    const result = buildPhraseBoost('foo bar', p(['pf', 'title^50'], ['ps', '-5']));
    const mm = result!.get('multi_match') as Map<string, any>;
    expect(mm.has('slop')).toBe(false);
  });

  it('uses the full query string as the phrase text', () => {
    const result = buildPhraseBoost('hello world java', p(['pf', 'title']));
    const mm = result!.get('multi_match') as Map<string, any>;
    expect(mm.get('query')).toBe('hello world java');
  });
});

// ─── applyPhraseBoost ─────────────────────────────────────────────────────────

describe('applyPhraseBoost', () => {
  const mainDsl = new Map([['multi_match', new Map()]]);

  it('returns mainDsl unchanged when pf is not set', () => {
    const result = applyPhraseBoost(mainDsl, 'foo bar', p());
    expect(result).toBe(mainDsl);
  });

  it('wraps in bool.must + bool.should when pf is set', () => {
    const result = applyPhraseBoost(mainDsl, 'foo bar', p(['pf', 'title^50']));
    const bool = result.get('bool') as Map<string, any>;
    expect(bool.get('must')).toBe(mainDsl);
    const should = bool.get('should') as Map<string, any>;
    expect(should.get('multi_match')).toBeDefined();
  });

  it('phrase boost in should has correct query and fields', () => {
    const result = applyPhraseBoost(mainDsl, 'foo bar', p(['pf', 'title^50 body^20'], ['ps', '5']));
    const mm = (result.get('bool') as Map<string, any>)
      .get('should').get('multi_match') as Map<string, any>;
    expect(mm.get('query')).toBe('foo bar');
    expect(mm.get('fields')).toEqual(['title^50', 'body^20']);
    expect(mm.get('type')).toBe('phrase');
    expect(mm.get('slop')).toBe(5);
  });
});
