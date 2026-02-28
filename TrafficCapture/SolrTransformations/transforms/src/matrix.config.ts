/**
 * Test matrix configuration — single source of truth for default versions.
 *
 * Test cases inherit these defaults unless they override with their own values.
 * To add a new Solr version to the matrix, just add it here — all tests pick it up.
 */
export const matrixConfig = {
  /** Default Solr Docker image tags. Every test case runs against each of these. */
  defaultSolrVersions: ['mirror.gcr.io/library/solr:8', 'mirror.gcr.io/library/solr:9'],

  /** Default OpenSearch image for the backend. */
  defaultOpenSearchImage: 'mirror.gcr.io/opensearchproject/opensearch:3.3.0',
};
