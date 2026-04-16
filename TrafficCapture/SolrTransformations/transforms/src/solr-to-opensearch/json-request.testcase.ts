/**
 * E2E test cases for JSON request body format on /select.
 *
 * Solr accepts queries as POST with JSON body using different key names
 * (query, limit, offset, etc.). The json-request transform normalizes
 * these to URL params before the rest of the pipeline runs.
 */
import { solrTest, SOLR_INTERNAL_RULES } from '../test-types';
import type { TestCase } from '../test-types';

export const testCases: TestCase[] = [
  solrTest('json-request-basic-query', {
    description: 'JSON body with query key should work like q= URL param',
    documents: [
      { id: '1', title: 'hello world' },
      { id: '2', title: 'goodbye world' },
    ],
    method: 'POST',
    requestBody: JSON.stringify({ query: '*:*' }),
    requestPath: '/solr/testcollection/select?wt=json',
    solrSchema: {
      fields: { title: { type: 'text_general' } },
    },
    opensearchMapping: {
      properties: { title: { type: 'text' } },
    },
    assertionRules: SOLR_INTERNAL_RULES,
  }),

  solrTest('json-request-with-limit-and-offset', {
    description: 'JSON body limit/offset should map to rows/start',
    documents: [
      { id: '1', title: 'doc one' },
      { id: '2', title: 'doc two' },
      { id: '3', title: 'doc three' },
    ],
    method: 'POST',
    requestBody: JSON.stringify({ query: '*:*', limit: 2, offset: 0 }),
    requestPath: '/solr/testcollection/select?wt=json',
    solrSchema: {
      fields: { title: { type: 'text_general' } },
    },
    opensearchMapping: {
      properties: { title: { type: 'text' } },
    },
    assertionRules: SOLR_INTERNAL_RULES,
  }),

  solrTest('json-request-with-fields', {
    description: 'JSON body fields should map to fl',
    documents: [
      { id: '1', title: 'hello', price: 10 },
      { id: '2', title: 'world', price: 20 },
    ],
    method: 'POST',
    requestBody: JSON.stringify({ query: '*:*', fields: 'id,title' }),
    requestPath: '/solr/testcollection/select?wt=json',
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
    assertionRules: SOLR_INTERNAL_RULES,
  }),

  solrTest('json-request-with-sort', {
    description: 'JSON body sort should map to sort param',
    documents: [
      { id: '1', title: 'alpha', price: 30 },
      { id: '2', title: 'beta', price: 10 },
      { id: '3', title: 'gamma', price: 20 },
    ],
    method: 'POST',
    requestBody: JSON.stringify({ query: '*:*', sort: 'price asc' }),
    requestPath: '/solr/testcollection/select?wt=json',
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
    assertionRules: SOLR_INTERNAL_RULES,
  }),

  solrTest('json-request-url-params-precedence', {
    description: 'URL params should take precedence over JSON body keys',
    documents: [
      { id: '1', title: 'doc one' },
      { id: '2', title: 'doc two' },
      { id: '3', title: 'doc three' },
    ],
    method: 'POST',
    // URL has rows=1, body has limit=99 — rows=1 should win
    requestBody: JSON.stringify({ query: '*:*', limit: 99 }),
    requestPath: '/solr/testcollection/select?rows=1&wt=json',
    solrSchema: {
      fields: { title: { type: 'text_general' } },
    },
    opensearchMapping: {
      properties: { title: { type: 'text' } },
    },
    assertionRules: SOLR_INTERNAL_RULES,
  }),

  solrTest('json-request-with-params-object', {
    description: 'JSON body params object should be merged into URL params',
    documents: [
      { id: '1', title: 'hello world' },
      { id: '2', title: 'goodbye world' },
    ],
    method: 'POST',
    requestBody: JSON.stringify({ query: '*:*', params: { rows: '1' } }),
    requestPath: '/solr/testcollection/select?wt=json',
    solrSchema: {
      fields: { title: { type: 'text_general' } },
    },
    opensearchMapping: {
      properties: { title: { type: 'text' } },
    },
    assertionRules: SOLR_INTERNAL_RULES,
  }),
];
