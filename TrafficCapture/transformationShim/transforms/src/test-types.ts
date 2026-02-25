/**
 * Test case type definitions and helpers.
 *
 * Use `solrTest()` to create test cases with sensible defaults — you only
 * specify what's unique about each test.
 */

/** Allowed field value types in test documents (matches Solr/OpenSearch field types). */
export type FieldValue = string | number | boolean | null | FieldValue[];

/** A test document to seed into Solr/OpenSearch. Must have an `id` field. */
export interface TestDocument {
  id: string;
  [field: string]: FieldValue;
}

/** OpenSearch field type names. */
export type OpenSearchFieldType =
  | 'text' | 'keyword' | 'constant_keyword' | 'wildcard'
  | 'long' | 'integer' | 'short' | 'byte' | 'double' | 'float' | 'half_float' | 'scaled_float'
  | 'date' | 'boolean' | 'binary' | 'ip'
  | 'object' | 'nested' | 'flattened'
  | 'geo_point' | 'geo_shape' | 'point' | 'shape'
  | 'completion' | 'search_as_you_type' | 'token_count'
  | 'dense_vector' | 'sparse_vector' | 'rank_feature' | 'rank_features'
  | 'alias' | 'join' | 'percolator' | 'knn_vector';

/** A single field mapping in an OpenSearch index. */
export interface OpenSearchFieldMapping {
  type?: OpenSearchFieldType;
  analyzer?: string;
  search_analyzer?: string;
  normalizer?: string;
  index?: boolean;
  store?: boolean;
  doc_values?: boolean;
  /** Sub-fields (e.g. keyword sub-field on a text field). */
  fields?: Record<string, OpenSearchFieldMapping>;
  /** Nested object properties. */
  properties?: Record<string, OpenSearchFieldMapping>;
}

/** OpenSearch index mapping definition. */
export interface OpenSearchMapping {
  properties: Record<string, OpenSearchFieldMapping>;
  dynamic?: boolean | 'strict' | 'runtime';
}

/** HTTP methods supported by the test framework. */
export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'DELETE' | 'HEAD';

/** A single test case definition. */
export interface TestCase {
  name: string;
  description?: string;
  requestTransforms: string[];
  responseTransforms: string[];
  collection: string;
  documents: TestDocument[];
  seedSolr?: boolean;
  seedOpenSearch?: boolean;
  /** HTTP method for the test request. Defaults to 'GET'. */
  method?: HttpMethod;
  /** Request body (for POST/PUT/DELETE). JSON-serializable. */
  requestBody?: string;
  requestPath: string;
  expectedDocs?: TestDocument[];
  expectedFields?: string[];
  assertResponseFormat?: 'solr' | 'opensearch';
  compareWithSolr?: boolean;
  ignorePaths?: string[];
  /** Explicit OpenSearch index mapping. If set, the index is created with this mapping before seeding. */
  opensearchMapping?: OpenSearchMapping;
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
 * - method: 'GET'
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
