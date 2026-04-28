/**
 * Test cases for Solr wildcard and fuzzy queries → OpenSearch transformation.
 *
 * These tests validate the full round-trip: PEG parser → AST → transformer → DSL
 * for wildcard (*, ?) and fuzzy (~, ~N) query patterns.
 */
import { solrTest } from '../../../test-types';
import type { TestCase } from '../../../test-types';

export const testCases: TestCase[] = [
  // ───────────────────────────────────────────────────────────
  // Wildcard queries (field:te?t, field:test* → wildcard query)
  // ───────────────────────────────────────────────────────────
  solrTest('query-wildcard-single-char', {
    description: 'Single character wildcard (?) matches one character',
    documents: [
      { id: '1', title: 'test', category: 'electronics' },
      { id: '2', title: 'text', category: 'electronics' },
      { id: '3', title: 'tent', category: 'clothing' },
      { id: '4', title: 'toast', category: 'food' },
    ],
    requestPath: '/solr/testcollection/select?q=title:te?t&wt=json',
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        category: { type: 'text_general' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        category: { type: 'text' },
      },
    },
  }),

  solrTest('query-wildcard-trailing-star', {
    description: 'Trailing wildcard (*) matches any suffix',
    documents: [
      { id: '1', title: 'search', category: 'technology' },
      { id: '2', title: 'searching', category: 'technology' },
      { id: '3', title: 'searcher', category: 'technology' },
      { id: '4', title: 'found', category: 'other' },
    ],
    requestPath: '/solr/testcollection/select?q=title:search*&wt=json',
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        category: { type: 'text_general' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        category: { type: 'text' },
      },
    },
  }),

  // ───────────────────────────────────────────────────────────
  // Fuzzy queries (field:roam~ → fuzzy query)
  // ───────────────────────────────────────────────────────────
  solrTest('query-fuzzy-default-distance', {
    description: 'Fuzzy query without distance uses default edit distance',
    documents: [
      { id: '1', title: 'roam', category: 'travel' },
      { id: '2', title: 'foam', category: 'material' },
      { id: '3', title: 'road', category: 'travel' },
      { id: '4', title: 'apple', category: 'food' },
    ],
    requestPath: '/solr/testcollection/select?q=title:roam~&wt=json',
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        category: { type: 'text_general' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        category: { type: 'text' },
      },
    },
  }),

  solrTest('query-fuzzy-with-distance', {
    description: 'Fuzzy query with explicit distance 2',
    documents: [
      { id: '1', title: 'roam', category: 'travel' },
      { id: '2', title: 'foam', category: 'material' },
      { id: '3', title: 'road', category: 'travel' },
      { id: '4', title: 'apple', category: 'food' },
    ],
    requestPath: '/solr/testcollection/select?q=title:roam~2&wt=json',
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        category: { type: 'text_general' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        category: { type: 'text' },
      },
    },
  }),
];
