/**
 * Test cases for Solr Standard Query Parser (Lucene) → OpenSearch transformation.
 *
 * These tests validate the query-engine's ability to parse and transform
 * Solr's standard query parser syntax into OpenSearch Query DSL.
 *
 * Adding a new test: just add a solrTest() entry below.
 * It automatically runs against every Solr version in matrix.config.ts.
 *
 * Each test case defines:
 * - solrSchema: the Solr collection's field types (applied via Schema API)
 * - opensearchMapping: the corresponding OpenSearch index mapping
 * - documents: data seeded into both backends
 * - requestPath: the Solr query to test
 * - assertionRules: expected differences from Solr (everything else must match exactly)
 */
import { solrTest } from '../../../test-types';
import type { TestCase } from '../../../test-types';

export const testCases: TestCase[] = [
  // ───────────────────────────────────────────────────────────
  // Range queries
  // ───────────────────────────────────────────────────────────

  solrTest('query-range-inclusive-exclusive', {
    description: 'Range queries with inclusive and exclusive bounds',
    documents: [
      { id: '1', title: 'cheap low stock', price: 10, stock: 5 },
      { id: '2', title: 'mid good stock', price: 50, stock: 25 },
      { id: '3', title: 'expensive high stock', price: 100, stock: 50 },
      { id: '4', title: 'luxury item', price: 500, stock: 10 },
      { id: '5', title: 'free item', price: 0, stock: 100 },
    ],
    // price:[10 TO 100] AND stock:{0 TO 50}
    requestPath: '/solr/testcollection/select?q=' + encodeURIComponent('price:[10 TO 100] AND stock:{0 TO 50}') + '&wt=json',
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        price: { type: 'pint' },
        stock: { type: 'pint' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        price: { type: 'integer' },
        stock: { type: 'integer' },
      },
    },
  }),

  solrTest('query-range-mixed-inclusive-exclusive', {
    description: 'Range query with inclusive lower and exclusive upper bound [10 TO 100}',
    documents: [
      { id: '1', title: 'item at lower bound', price: 10 },
      { id: '2', title: 'item in middle', price: 50 },
      { id: '3', title: 'item at upper bound', price: 100 },
      { id: '4', title: 'item above upper', price: 150 },
    ],
    // price:[10 TO 100} — includes 10, excludes 100
    requestPath: '/solr/testcollection/select?q=' + encodeURIComponent('price:[10 TO 100}') + '&wt=json',
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        price: { type: 'pint' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        price: { type: 'integer' },
      },
    },
  }),

  solrTest('query-range-unbounded-upper', {
    description: 'Range query with unbounded upper bound [10 TO *]',
    documents: [
      { id: '1', title: 'below threshold', price: 5 },
      { id: '2', title: 'at threshold', price: 10 },
      { id: '3', title: 'above threshold', price: 100 },
      { id: '4', title: 'way above', price: 1000 },
    ],
    // price:[10 TO *] — 10 and above
    requestPath: '/solr/testcollection/select?q=' + encodeURIComponent('price:[10 TO *]') + '&wt=json',
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        price: { type: 'pint' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        price: { type: 'integer' },
      },
    },
  }),

  solrTest('query-range-fully-unbounded-exists', {
    description: 'Range query [* TO *] matches documents where field exists',
    documents: [
      { id: '1', title: 'has price', price: 50 },
      { id: '2', title: 'also has price', price: 0 },
      { id: '3', title: 'no price field' },
    ],
    // price:[* TO *] — field exists check
    requestPath: '/solr/testcollection/select?q=' + encodeURIComponent('price:[* TO *]') + '&wt=json',
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        price: { type: 'pint' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        price: { type: 'integer' },
      },
    },
  }),
];
