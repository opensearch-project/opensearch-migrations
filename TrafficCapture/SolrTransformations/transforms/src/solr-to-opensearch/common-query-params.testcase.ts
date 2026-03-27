/**
 * Test cases for the Solr → OpenSearch transformation for common Solr query parameters.
 * Reference: https://solr.apache.org/guide/solr/latest/query-guide/common-query-parameters.html
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
import { solrTest } from '../test-types';
import type { TestCase } from '../test-types';

export const testCases: TestCase[] = [
  // ───────────────────────────────────────────────────────────
  // Pagination tests — rows and start parameters
  // ───────────────────────────────────────────────────────────

  solrTest('rows-limits-returned-documents', {
    documents: [
      { id: '1', title: 'first doc', content: 'alpha' },
      { id: '2', title: 'second doc', content: 'beta' },
      { id: '3', title: 'third doc', content: 'gamma' },
    ],
    requestPath: '/solr/testcollection/select?q=*:*&rows=2&wt=json',
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        content: { type: 'text_general' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        content: { type: 'text' },
      },
    },
  }),

  solrTest('rows-with-start-pagination', {
    documents: [
      { id: '1', title: 'first doc', content: 'alpha' },
      { id: '2', title: 'second doc', content: 'beta' },
      { id: '3', title: 'third doc', content: 'gamma' },
      { id: '4', title: 'fourth doc', content: 'delta' },
    ],
    requestPath: '/solr/testcollection/select?q=*:*&rows=2&start=2&wt=json',
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        content: { type: 'text_general' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        content: { type: 'text' },
      },
    },
  }),

  // ───────────────────────────────────────────────────────────
  // Field list (fl) tests — _source filtering
  // ───────────────────────────────────────────────────────────

  solrTest('field-list-param', {
    description: 'fl parameter with mixed comma/space separators and glob pattern (na*)',
    documents: [
      { id: '1', name: 'Alice', name_full: 'Alice Smith', price: 100, category: 'user' },
      { id: '2', name: 'Bob', name_full: 'Bob Jones', price: 200, category: 'admin' },
    ],
    requestPath: '/solr/testcollection/select?q=*:*&wt=json&fl=' + encodeURIComponent('id,na* price'),
    solrSchema: {
      fields: {
        name: { type: 'text_general' },
        name_full: { type: 'text_general' },
        price: { type: 'pint' },
        category: { type: 'text_general' },
      },
    },
    opensearchMapping: {
      properties: {
        name: { type: 'text' },
        name_full: { type: 'text' },
        price: { type: 'integer' },
        category: { type: 'text' },
      },
    },
  }),

  // ───────────────────────────────────────────────────────────
  // Sort tests — sort parameter
  // ───────────────────────────────────────────────────────────

  solrTest('sort-multiple-fields', {
    description: 'sort parameter with multiple fields',
    documents: [
      { id: '1', title: 'a', quantity: 10, price: 200 },
      { id: '2', title: 'b', quantity: 10, price: 100 },
      { id: '3', title: 'c', quantity: 5, price: 150 },
      { id: '4', title: 'd', quantity: 5, price: 50 },
    ],
    requestPath: '/solr/testcollection/select?q=*:*&wt=json&sort=' + encodeURIComponent('quantity asc, price desc'),
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        quantity: { type: 'pint' },
        price: { type: 'pint' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        quantity: { type: 'integer' },
        price: { type: 'integer' },
      },
    },
  }),
];
