/**
 * Test cases for the Solr → OpenSearch transformation.
 */
import type { TestCase } from '../test-types';

export const testCases: TestCase[] = [
  {
    name: 'basic-select-all-transforms',
    description: 'Full Solr→OpenSearch transform: URI rewrite + response format conversion',
    requestTransforms: ['solr-to-opensearch-request'],
    responseTransforms: ['solr-to-opensearch-response'],
    collection: 'testcollection',
    documents: [
      { id: '1', title: 'test document', content: 'hello world' },
    ],
    requestPath: '/solr/testcollection/select?q=*:*&wt=json',
    expectedDocs: [
      { id: '1', title: 'test document', content: 'hello world' },
    ],
    assertResponseFormat: 'solr',
  },
  {
    name: 'multiple-documents',
    description: 'Verify multiple documents are returned and transformed correctly',
    requestTransforms: ['solr-to-opensearch-request'],
    responseTransforms: ['solr-to-opensearch-response'],
    collection: 'testcollection',
    documents: [
      { id: '1', title: 'first doc', content: 'alpha' },
      { id: '2', title: 'second doc', content: 'beta' },
      { id: '3', title: 'third doc', content: 'gamma' },
    ],
    requestPath: '/solr/testcollection/select?q=*:*&wt=json',
    expectedDocs: [
      { id: '1', title: 'first doc', content: 'alpha' },
      { id: '2', title: 'second doc', content: 'beta' },
      { id: '3', title: 'third doc', content: 'gamma' },
    ],
    expectedFields: ['id', 'title', 'content'],
    assertResponseFormat: 'solr',
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
