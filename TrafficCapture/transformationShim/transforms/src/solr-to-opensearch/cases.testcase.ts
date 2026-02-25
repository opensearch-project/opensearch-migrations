/**
 * Test cases for the Solr → OpenSearch transformation.
 */
import type { TestCase } from '../test-types';

export const testCases: TestCase[] = [
  {
    name: 'basic-select-compare-with-solr',
    description: 'Full Solr→OpenSearch transform: compare proxy response against real Solr',
    requestTransforms: ['solr-to-opensearch-request'],
    responseTransforms: ['solr-to-opensearch-response'],
    collection: 'testcollection',
    documents: [
      { id: '1', title: 'test document', content: 'hello world' },
    ],
    requestPath: '/solr/testcollection/select?q=*:*&wt=json',
    compareWithSolr: true,
    ignorePaths: [
      '$.responseHeader.QTime',
      '$.responseHeader.params',
    ],
  },
  {
    name: 'multiple-documents-compare-with-solr',
    description: 'Multiple documents: compare proxy response against real Solr',
    requestTransforms: ['solr-to-opensearch-request'],
    responseTransforms: ['solr-to-opensearch-response'],
    collection: 'testcollection',
    documents: [
      { id: '1', title: 'first doc', content: 'alpha' },
      { id: '2', title: 'second doc', content: 'beta' },
      { id: '3', title: 'third doc', content: 'gamma' },
    ],
    requestPath: '/solr/testcollection/select?q=*:*&wt=json',
    compareWithSolr: true,
    ignorePaths: [
      '$.responseHeader.QTime',
      '$.responseHeader.params',
    ],
  },
  {
    name: 'uri-rewrite-only-opensearch-format',
    description: 'Only URI rewrite applied — response stays in raw OpenSearch format',
    requestTransforms: ['solr-to-opensearch-request'],
    responseTransforms: [],
    collection: 'testcollection',
    documents: [
      { id: '1', title: 'test' },
    ],
    seedSolr: false,
    requestPath: '/solr/testcollection/select?q=*:*',
    assertResponseFormat: 'opensearch',
  },
];
