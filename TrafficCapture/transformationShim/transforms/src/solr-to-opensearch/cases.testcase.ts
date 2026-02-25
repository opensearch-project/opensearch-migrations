/**
 * Test cases for the Solr → OpenSearch transformation.
 *
 * Adding a new test: just add a solrTest() entry below.
 * It automatically runs against every Solr version in matrix.config.ts.
 */
import { solrTest } from '../test-types';
import type { TestCase } from '../test-types';

export const testCases: TestCase[] = [
  solrTest('basic-select-compare-with-solr', {
    documents: [{ id: '1', title: 'test document', content: 'hello world' }],
    requestPath: '/solr/testcollection/select?q=*:*&wt=json',
  }),

  solrTest('multiple-documents-compare-with-solr', {
    documents: [
      { id: '1', title: 'first doc', content: 'alpha' },
      { id: '2', title: 'second doc', content: 'beta' },
      { id: '3', title: 'third doc', content: 'gamma' },
    ],
    requestPath: '/solr/testcollection/select?q=*:*&wt=json',
  }),

  solrTest('uri-rewrite-only-opensearch-format', {
    documents: [{ id: '1', title: 'test' }],
    requestPath: '/solr/testcollection/select?q=*:*',
    responseTransforms: [],
    seedSolr: false,
    compareWithSolr: false,
    assertResponseFormat: 'opensearch',
  }),

  // TODO: Enable once request transform handles fq (filter query) params.
  // This test will FAIL with compareWithSolr because the proxy ignores fq,
  // so Solr returns 2 docs (category:animal) while the proxy returns all 3.
  // That diff is exactly what proves the framework catches transform gaps.
  //
  // solrTest('filter-query-fq', {
  //   documents: [
  //     { id: '1', title: 'cat', category: 'animal' },
  //     { id: '2', title: 'dog', category: 'animal' },
  //     { id: '3', title: 'car', category: 'vehicle' },
  //   ],
  //   opensearchMapping: {
  //     properties: {
  //       title: { type: 'text' },
  //       category: { type: 'keyword' },
  //     },
  //   },
  //   requestPath: '/solr/testcollection/select?q=*:*&fq=category:animal&wt=json',
  // }),
];
