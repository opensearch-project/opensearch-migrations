/**
 * Type definitions for declarative test cases.
 *
 * Each test case defines: which transforms to apply, what data to seed,
 * what request to send, and what to assert about the response.
 */

/** A single test case definition. */
export interface TestCase {
  /** Unique test name (used as JUnit display name). */
  name: string;

  /** Optional description. */
  description?: string;

  /** Request transform JS filenames to compose (e.g., ["solr-to-opensearch-request"]). */
  requestTransforms: string[];

  /** Response transform JS filenames to compose (e.g., ["solr-to-opensearch-response"]). */
  responseTransforms: string[];

  /** Collection name — used as both Solr core and OpenSearch index. */
  collection: string;

  /** Documents to seed. */
  documents: Record<string, unknown>[];

  /** Seed documents to Solr? Default: true. */
  seedSolr?: boolean;

  /** Seed documents to OpenSearch? Default: true. */
  seedOpenSearch?: boolean;

  /** Solr-format request path to send through the proxy. */
  requestPath: string;

  /** Expected documents in the response (field values to assert). */
  expectedDocs?: Record<string, unknown>[];

  /** Which fields to compare. Default: all keys in expectedDocs[0]. */
  expectedFields?: string[];

  /** Assert the response structure format. */
  assertResponseFormat?: 'solr' | 'opensearch';

  /** If true, query real Solr and compare full response against proxy response. */
  compareWithSolr?: boolean;

  /** Dot-separated JSON paths to ignore in compareWithSolr diff (e.g. "$.responseHeader.QTime"). */
  ignorePaths?: string[];

  /** Solr Docker image tags to test against (e.g. ["solr:8", "solr:9"]). Inherits from matrix config if omitted. */
  solrVersions?: string[];

  /** Solr plugins required for this test case (e.g. ["analysis-icu"]). */
  plugins?: string[];
}
