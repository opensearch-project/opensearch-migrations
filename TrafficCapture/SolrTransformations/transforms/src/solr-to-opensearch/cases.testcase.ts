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
 * - assertionRules: how to handle expected differences
 * - responseAssertions: verify the response content is correct
 */
import { solrTest } from '../test-types';
import type { TestCase } from '../test-types';

export const testCases: TestCase[] = [
  solrTest('basic-select-compare-with-solr', {
    documents: [{ id: '1', title: 'test document', content: 'hello world' }],
    requestPath: '/solr/testcollection/select?q=*:*&wt=json',
    solrSchema: {
      fields: {
        title:   { type: 'text_general' },
        content: { type: 'text_general' },
      },
    },
    opensearchMapping: {
      properties: {
        title:   { type: 'text' },
        content: { type: 'text' },
      },
    },
    responseAssertions: [
      { path: '$.response.numFound', equals: 1 },
      { path: '$.response.docs', count: 1 },
      { path: '$.response.docs[0].id', equals: '1' },
    ],
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
        title:   { type: 'text_general' },
        content: { type: 'text_general' },
      },
    },
    opensearchMapping: {
      properties: {
        title:   { type: 'text' },
        content: { type: 'text' },
      },
    },
    responseAssertions: [
      { path: '$.response.numFound', equals: 3 },
      { path: '$.response.docs', count: 3 },
    ],
  }),
];
