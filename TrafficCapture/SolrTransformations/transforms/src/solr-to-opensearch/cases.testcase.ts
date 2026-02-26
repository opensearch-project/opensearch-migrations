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
 */
import { solrTest, SOLR_INTERNAL_RULES } from '../test-types';
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
  }),

  solrTest('uri-rewrite-only-opensearch-format', {
    documents: [{ id: '1', title: 'test' }],
    requestPath: '/solr/testcollection/select?q=*:*',
    responseTransforms: [],
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
      },
    },
    assertionRules: [
      ...SOLR_INTERNAL_RULES,
      { path: '$.responseHeader', rule: 'expect-diff', reason: 'No response transform — proxy returns raw OpenSearch format without Solr responseHeader' },
      { path: '$.response', rule: 'expect-diff', reason: 'No response transform — Solr returns response object, proxy returns raw OpenSearch hits' },
      { path: '$.hits', rule: 'expect-diff', reason: 'No response transform — OpenSearch returns hits instead of response' },
      { path: '$.took', rule: 'expect-diff', reason: 'No response transform — OpenSearch timing field not in Solr' },
      { path: '$.timed_out', rule: 'expect-diff', reason: 'No response transform — OpenSearch field not in Solr' },
      { path: '$._shards', rule: 'expect-diff', reason: 'No response transform — OpenSearch shard info not in Solr' },
    ],
  }),

  solrTest('filter-query-fq', {
    documents: [
      { id: '1', title: 'cat', category: 'animal' },
      { id: '2', title: 'dog', category: 'animal' },
      { id: '3', title: 'car', category: 'vehicle' },
    ],
    solrSchema: {
      fields: {
        title:    { type: 'text_general' },
        category: { type: 'string' },  // exact match — Solr 'string' maps to OpenSearch 'keyword'
      },
    },
    opensearchMapping: {
      properties: {
        title:    { type: 'text' },
        category: { type: 'keyword' },
      },
    },
    requestPath: '/solr/testcollection/select?q=*:*&fq=category:animal&wt=json',
    assertionRules: [
      ...SOLR_INTERNAL_RULES,
      { path: '$.response.numFound', rule: 'expect-diff', reason: 'fq not implemented — proxy returns all docs' },
      { path: '$.response.docs', rule: 'expect-diff', reason: 'fq not implemented — doc count and content will differ' },
    ],
  }),
];
