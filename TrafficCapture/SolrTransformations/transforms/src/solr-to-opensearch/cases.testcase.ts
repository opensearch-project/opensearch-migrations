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

  solrTest('facet-basic-range', {
    description: 'Basic range facet on a numeric field using start/end/gap',
    documents: [
      { id: '1', title: 'cheap item', price: 10 },
      { id: '2', title: 'mid item', price: 35 },
      { id: '3', title: 'pricey item', price: 55 },
      { id: '4', title: 'expensive item', price: 80 },
      { id: '5', title: 'luxury item', price: 95 },
    ],
    requestPath:
      '/solr/testcollection/select?q=*:*&wt=json&json.facet=' +
      encodeURIComponent(
        JSON.stringify({
          prices: { type: 'range', field: 'price', start: 0, end: 100, gap: 20 },
        }),
      ),
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        price: { type: 'pfloat' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        price: { type: 'float' },
      },
    },
    assertionRules: [
      ...SOLR_INTERNAL_RULES,
      {
        path: '$.response',
        rule: 'ignore',
        reason: 'Facet test — only validating $.facets, not hits',
      },
    ],
  }),

  solrTest('facet-arbitrary-range', {
    description: 'Arbitrary range facet with custom bucket boundaries',
    documents: [
      { id: '1', title: 'cheap item', price: 10 },
      { id: '2', title: 'mid item', price: 35 },
      { id: '3', title: 'pricey item', price: 55 },
      { id: '4', title: 'expensive item', price: 80 },
      { id: '5', title: 'luxury item', price: 95 },
    ],
    requestPath:
      '/solr/testcollection/select?q=*:*&wt=json&json.facet=' +
      encodeURIComponent(
        JSON.stringify({
          price_ranges: {
            type: 'range',
            field: 'price',
            ranges: [
              { range: '[0,25)' },
              { range: '[25,50)' },
              { range: '[50,75)' },
              { range: '[75,*)' },
            ],
          },
        }),
      ),
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        price: { type: 'pfloat' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        price: { type: 'float' },
      },
    },
    assertionRules: [
      ...SOLR_INTERNAL_RULES,
      {
        path: '$.response',
        rule: 'ignore',
        reason: 'Facet test — only validating $.facets, not hits',
      },
    ],
  }),
];
