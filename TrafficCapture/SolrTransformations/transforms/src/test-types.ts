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

/** Solr field type names (common built-in types). */
export type SolrFieldType =
  | 'text_general' | 'text_en' | 'text_ws' | 'text_en_splitting'
  | 'string' | 'strings'
  | 'pint' | 'plong' | 'pfloat' | 'pdouble'
  | 'pints' | 'plongs' | 'pfloats' | 'pdoubles'
  | 'boolean' | 'booleans'
  | 'pdate' | 'pdates'
  | 'binary'
  | 'text_general_rev' | 'alphaOnlySort' | 'phonetic' | 'payloads'
  | 'lowercase' | 'descendent_path' | 'ancestor_path'
  | 'point' | 'location' | 'location_rpt'
  | 'currency' | 'rank';

/** A single field definition in a Solr schema. */
export interface SolrFieldDefinition {
  type: SolrFieldType;
  multiValued?: boolean;
  indexed?: boolean;
  stored?: boolean;
  required?: boolean;
  docValues?: boolean;
}

/**
 * Solr collection schema definition.
 *
 * Defines the explicit field types for a Solr collection. When set on a test case,
 * the Java harness applies these via the Solr Schema API after creating the core.
 * This makes it clear what Solr field types are in play, so you can reason about
 * the corresponding OpenSearch mapping.
 */
export interface SolrSchema {
  fields: Record<string, SolrFieldDefinition>;
}

/** HTTP methods supported by the test framework. */
export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'DELETE' | 'HEAD';

/** Assertion rule types for controlling how diffs are handled per JSON path. */
export type AssertionRuleType =
  | 'ignore'       // Skip this path entirely
  | 'loose-order'  // Compare arrays as sets (ignore ordering)
  | 'loose-type'   // Allow numeric type coercion (1 == 1.0)
  | 'expect-diff'  // Known difference — test passes, diff logged as info
  | 'regex';       // Match actual value against a regex pattern

/**
 * A per-path assertion rule that controls how differences are handled.
 *
 * Every test always compares with real Solr. Rules let you be explicit
 * about which differences are expected and why.
 */
export interface AssertionRule {
  /** JSONPath to match (e.g. '$.responseHeader.QTime'). Supports [*] for array wildcards. */
  path: string;
  /** How to handle differences at this path. */
  rule: AssertionRuleType;
  /** For 'regex' rule: the pattern to match against the actual value. */
  expected?: string;
  /** Documentation: WHY this rule exists. Shows up in diff reports. */
  reason?: string;
}

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
  /** Per-path assertion rules controlling how diffs are handled. */
  assertionRules?: AssertionRule[];
  /**
   * Solr collection schema — defines the field types for this test's Solr collection.
   * When set, the Java harness applies these via the Solr Schema API after creating the core.
   * Makes it explicit what Solr field types are in play for each test case.
   */
  solrSchema?: SolrSchema;
  /** Explicit OpenSearch index mapping. If set, the index is created with this mapping before seeding. */
  opensearchMapping?: OpenSearchMapping;
  solrVersions?: string[];
  plugins?: string[];
}

/** Solr-internal fields that OpenSearch doesn't have — always safe to ignore. */
export const SOLR_INTERNAL_RULES: AssertionRule[] = [
  { path: '$.responseHeader.QTime', rule: 'ignore', reason: 'Timing varies per request' },
  { path: '$.responseHeader.params', rule: 'ignore', reason: 'Solr echoes params, proxy does not' },
  { path: '$.response.docs[*]._version_', rule: 'ignore', reason: 'Solr-internal optimistic concurrency field' },
  { path: '$.response.docs[*]._root_', rule: 'ignore', reason: 'Solr-internal nested doc root field' },
];

/**
 * Create a Solr→OpenSearch E2E test case with sensible defaults.
 *
 * Every test always compares with real Solr. Use assertionRules to
 * declare expected differences per path.
 *
 * Defaults:
 * - method: 'GET'
 * - requestTransforms: ['solr-to-opensearch-request']
 * - responseTransforms: ['solr-to-opensearch-response']
 * - collection: 'testcollection'
 * - assertionRules: SOLR_INTERNAL_RULES
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
    assertionRules: SOLR_INTERNAL_RULES,
    ...overrides,
  };
}
