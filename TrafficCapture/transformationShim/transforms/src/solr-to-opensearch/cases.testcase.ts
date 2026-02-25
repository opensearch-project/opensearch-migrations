/**
 * Test cases for the Solr → OpenSearch transformation.
 *
 * Adding a new test: just add an entry below. It automatically runs against
 * every Solr version in matrix.config.ts (or override with solrVersions).
 */
import type { TestCase } from '../test-types';

/** Solr-internal fields that OpenSearch doesn't have — always safe to ignore. */
const SOLR_INTERNAL_IGNORE = [
  '$.responseHeader.QTime',
  '$.responseHeader.params',
  '$.response.docs[*]._version_',
  '$.response.docs[*]._root_',
];

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
    ignorePaths: SOLR_INTERNAL_IGNORE,
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
    ignorePaths: SOLR_INTERNAL_IGNORE,
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
