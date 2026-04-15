/**
 * E2E test cases for single document update via /update/json/docs.
 *
 * These run against real Solr 8/9 + OpenSearch containers.
 * The response transform converts OpenSearch's _doc response to Solr's
 * update response format, so the standard comparison framework works.
 */
import { solrTest } from '../test-types';
import type { TestCase } from '../test-types';

/** Update responses only have responseHeader — QTime varies, params not echoed by proxy. */
const UPDATE_RESPONSE_RULES = [
  { path: '$.responseHeader.QTime', rule: 'ignore' as const, reason: 'Timing varies per request' },
  { path: '$.responseHeader.params', rule: 'ignore' as const, reason: 'Solr echoes params, proxy does not' },
];

export const testCases: TestCase[] = [
  solrTest('update-single-doc-basic', {
    description: 'Single document ingestion via /update/json/docs',
    documents: [],
    method: 'POST',
    requestBody: JSON.stringify({ id: '1', title: 'hello world' }),
    requestPath: '/solr/testcollection/update/json/docs?commit=true',
    solrSchema: {
      fields: { title: { type: 'text_general' } },
    },
    opensearchMapping: {
      properties: { title: { type: 'text' } },
    },
    assertionRules: UPDATE_RESPONSE_RULES,
  }),

  solrTest('update-single-doc-multiple-fields', {
    description: 'Document with multiple field types',
    documents: [],
    method: 'POST',
    requestBody: JSON.stringify({ id: '2', title: 'multi-field', price: 19.99, inStock: true }),
    requestPath: '/solr/testcollection/update/json/docs?commit=true',
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        price: { type: 'pfloat' },
        inStock: { type: 'boolean' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        price: { type: 'float' },
        inStock: { type: 'boolean' },
      },
    },
    assertionRules: UPDATE_RESPONSE_RULES,
  }),

  solrTest('update-single-doc-without-commit', {
    description: 'Document ingestion without commit=true param',
    documents: [],
    method: 'POST',
    requestBody: JSON.stringify({ id: '3', title: 'no commit' }),
    requestPath: '/solr/testcollection/update/json/docs',
    solrSchema: {
      fields: { title: { type: 'text_general' } },
    },
    opensearchMapping: {
      properties: { title: { type: 'text' } },
    },
    assertionRules: UPDATE_RESPONSE_RULES,
  }),

  solrTest('update-single-doc-special-chars-in-id', {
    description: 'Document with special characters in id',
    documents: [],
    method: 'POST',
    requestBody: JSON.stringify({ id: 'doc-with-dashes_and_underscores', title: 'special id' }),
    requestPath: '/solr/testcollection/update/json/docs?commit=true',
    solrSchema: {
      fields: { title: { type: 'text_general' } },
    },
    opensearchMapping: {
      properties: { title: { type: 'text' } },
    },
    assertionRules: UPDATE_RESPONSE_RULES,
  }),

  solrTest('update-single-doc-overwrite', {
    description: 'Updating an existing document should overwrite it',
    documents: [{ id: '1', title: 'original' }],
    method: 'POST',
    requestBody: JSON.stringify({ id: '1', title: 'updated' }),
    requestPath: '/solr/testcollection/update/json/docs?commit=true',
    solrSchema: {
      fields: { title: { type: 'text_general' } },
    },
    opensearchMapping: {
      properties: { title: { type: 'text' } },
    },
    assertionRules: UPDATE_RESPONSE_RULES,
  }),

  // ───────────────────────────────────────────────────────────
  // Error paths
  // ───────────────────────────────────────────────────────────

  solrTest('update-error-missing-id', {
    description: 'Document without id field should return 500',
    documents: [],
    method: 'POST',
    requestBody: JSON.stringify({ title: 'no id here' }),
    requestPath: '/solr/testcollection/update/json/docs',
    expectedStatusCode: 500,
    expectedErrorContains: 'Request transform failed',
  }),

  solrTest('update-error-empty-body', {
    description: 'Empty body should return 500',
    documents: [],
    method: 'POST',
    requestBody: JSON.stringify({}),
    requestPath: '/solr/testcollection/update/json/docs',
    expectedStatusCode: 500,
    expectedErrorContains: 'Request transform failed',
  }),

  solrTest('update-commitWithin-translates-to-refresh', {
    description: 'commitWithin param should translate to immediate refresh',
    documents: [],
    method: 'POST',
    requestBody: JSON.stringify({ id: '1', title: 'hello' }),
    requestPath: '/solr/testcollection/update/json/docs?commitWithin=10000',
    solrSchema: {
      fields: { title: { type: 'text_general' } },
    },
    opensearchMapping: {
      properties: { title: { type: 'text' } },
    },
    assertionRules: UPDATE_RESPONSE_RULES,
  }),
];
