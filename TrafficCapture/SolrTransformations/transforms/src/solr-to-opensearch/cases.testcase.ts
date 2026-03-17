/**
 * Test cases for the Solr → OpenSearch transformation.
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
import { solrTest, SOLR_INTERNAL_RULES } from '../test-types';
import type { TestCase } from '../test-types';

export const testCases: TestCase[] = [
  solrTest('basic-select-compare-with-solr', {
    documents: [{ id: '1', title: 'test document', content: 'hello world' }],
    requestPath: '/solr/testcollection/select?q=*:*&wt=json',
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

  solrTest('multiple-documents-compare-with-solr', {
    documents: [
      { id: '1', title: 'first doc', content: 'alpha' },
      { id: '2', title: 'second doc', content: 'beta' },
      { id: '3', title: 'third doc', content: 'gamma' },
    ],
    requestPath: '/solr/testcollection/select?q=*:*&wt=json',
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
  // Facet tests — JSON Facet API (json.facet)
  // ───────────────────────────────────────────────────────────

  solrTest('facet-basic-terms', {
    description: 'Basic terms facet on a keyword field with distinct counts',
    documents: [
      { id: '1', title: 'laptop', category: 'electronics' },
      { id: '2', title: 'phone', category: 'electronics' },
      { id: '3', title: 'phone case', category: 'electronics' },
      { id: '4', title: 'shirt', category: 'clothing' },
      { id: '5', title: 'pants', category: 'clothing' },
      { id: '6', title: 'apple', category: 'food' },
    ],
    requestPath:
      '/solr/testcollection/select?q=*:*&wt=json&json.facet=' +
      encodeURIComponent(JSON.stringify({
        categories: { type: 'terms', field: 'category', sort: 'count desc' },
      })),
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        category: { type: 'string' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        category: { type: 'keyword' },
      },
    },
    assertionRules: [
      ...SOLR_INTERNAL_RULES,
      { path: '$.response', rule: 'ignore', reason: 'Facet test — only validating $.facets, not hits' },
    ],
  }),

  // ───────────────────────────────────────────────────────────
  // Field list (fl) tests — _source filtering
  // ───────────────────────────────────────────────────────────

  solrTest('field-list-param', {
    description: 'fl parameter with mixed comma/space separators and glob pattern (na*)',
    documents: [
      { id: '1', name: 'Alice', name_full: 'Alice Smith', price: 100 },
      { id: '2', name: 'Bob', name_full: 'Bob Jones', price: 200 },
    ],
    requestPath: '/solr/testcollection/select?q=*:*&fl=id,na*%20price&wt=json',
    solrSchema: {
      fields: {
        name: { type: 'text_general' },
        name_full: { type: 'text_general' },
        price: { type: 'pint' },
      },
    },
    opensearchMapping: {
      properties: {
        name: { type: 'text' },
        name_full: { type: 'text' },
        price: { type: 'integer' },
      },
    },
  }),
];
