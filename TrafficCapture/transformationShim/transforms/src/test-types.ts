/**
 * Test case type definitions and helpers.
 *
 * Use `solrTest()` to create test cases with sensible defaults — you only
 * specify what's unique about each test.
 */

/** A single test case definition. */
export interface TestCase {
  name: string;
  description?: string;
  requestTransforms: string[];
  responseTransforms: string[];
  collection: string;
  documents: Record<string, unknown>[];
  seedSolr?: boolean;
  seedOpenSearch?: boolean;
  requestPath: string;
  expectedDocs?: Record<string, unknown>[];
  expectedFields?: string[];
  assertResponseFormat?: 'solr' | 'opensearch';
  compareWithSolr?: boolean;
  ignorePaths?: string[];
  solrVersions?: string[];
  plugins?: string[];
}

/** Solr-internal fields that OpenSearch doesn't have — always safe to ignore. */
export const SOLR_INTERNAL_IGNORE = [
  '$.responseHeader.QTime',
  '$.responseHeader.params',
  '$.response.docs[*]._version_',
  '$.response.docs[*]._root_',
];

/**
 * Create a Solr→OpenSearch E2E test case with sensible defaults.
 *
 * Defaults:
 * - requestTransforms: ['solr-to-opensearch-request']
 * - responseTransforms: ['solr-to-opensearch-response']
 * - collection: 'testcollection'
 * - compareWithSolr: true
 * - ignorePaths: SOLR_INTERNAL_IGNORE
 *
 * Example — minimal test case:
 * ```
 * solrTest('basic-select', {
 *   documents: [{ id: '1', title: 'hello' }],
 *   requestPath: '/solr/testcollection/select?q=*:*&wt=json',
 * })
 * ```
 */
export function solrTest(
  name: string,
  overrides: Partial<TestCase> & Pick<TestCase, 'documents' | 'requestPath'>
): TestCase {
  return {
    name,
    requestTransforms: ['solr-to-opensearch-request'],
    responseTransforms: ['solr-to-opensearch-response'],
    collection: 'testcollection',
    compareWithSolr: true,
    ignorePaths: SOLR_INTERNAL_IGNORE,
    ...overrides,
  };
}
