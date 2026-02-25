/**
 * Test matrix configuration — single source of truth for default versions.
 *
 * Test cases inherit these defaults unless they override with their own values.
 * To add a new Solr version to the matrix, just add it here — all tests pick it up.
 */
export const matrixConfig = {
  /** Default Solr Docker image tags. Every test case runs against each of these. */
  defaultSolrVersions: ['solr:8', 'solr:9'],

  /** Default OpenSearch image for the backend. */
  defaultOpenSearchImage: 'opensearchproject/opensearch:3.0.0',
};
