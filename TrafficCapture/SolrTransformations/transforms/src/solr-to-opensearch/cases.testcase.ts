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
    responseAssertions: [
      // Response structure
      { path: '$.response.numFound', equals: 1 },
      { path: '$.response.start', equals: 0 },
      { path: '$.response.numFoundExact', equals: true },
      { path: '$.response.docs', count: 1 },
      // Doc fields — would fail if transform drops fields
      { path: '$.response.docs[0].id', equals: '1' },
      { path: '$.response.docs[0].title', exists: true },
      { path: '$.response.docs[0].content', exists: true },
      // _version_ — would have caught the missing _version_ bug
      { path: '$.response.docs[0]._version_', exists: true },
      // responseHeader — would have caught missing responseHeader.params
      { path: '$.responseHeader.status', equals: 0 },
      { path: '$.responseHeader.params', exists: true },
      { path: '$.responseHeader.params.q', equals: '*:*' },
      { path: '$.responseHeader.params.wt', equals: 'json' },
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
    responseAssertions: [
      // Response structure
      { path: '$.response.numFound', equals: 3 },
      { path: '$.response.start', equals: 0 },
      { path: '$.response.numFoundExact', equals: true },
      { path: '$.response.docs', count: 3 },
      // _version_ on every doc — would have caught the missing _version_ bug
      { path: '$.response.docs[0]._version_', exists: true },
      { path: '$.response.docs[1]._version_', exists: true },
      { path: '$.response.docs[2]._version_', exists: true },
      // responseHeader — would have caught missing responseHeader.params
      { path: '$.responseHeader.status', equals: 0 },
      { path: '$.responseHeader.params', exists: true },
      { path: '$.responseHeader.params.q', equals: '*:*' },
      { path: '$.responseHeader.params.wt', equals: 'json' },
    ],
  }),
];
