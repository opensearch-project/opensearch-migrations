/**
 * Test cases for Solr Standard Query Parser phrase queries → OpenSearch transformation.
 *
 * These tests validate the query-engine's ability to parse and transform
 * Solr's field:"phrase" syntax into OpenSearch match_phrase queries.
 *
 * Adding a new test: just add a solrTest() entry below.
 * It automatically runs against every Solr version in matrix.config.ts.
 */
import { solrTest } from '../../../test-types';
import type { TestCase } from '../../../test-types';

export const testCases: TestCase[] = [
  // ───────────────────────────────────────────────────────────
  // Phrase queries
  // ───────────────────────────────────────────────────────────

  solrTest('query-phrase-simple', {
    description: 'Simple phrase query on a text field',
    documents: [
      { id: '1', title: 'the quick brown fox' },
      { id: '2', title: 'quick fox brown' },
      { id: '3', title: 'the slow brown dog' },
    ],
    requestPath: '/solr/testcollection/select?q=' + encodeURIComponent('title:"quick brown"') + '&wt=json',
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
      },
    },
  }),

  solrTest('query-phrase-no-match', {
    description: 'Phrase query that matches no documents',
    documents: [
      { id: '1', title: 'the quick brown fox' },
      { id: '2', title: 'brown quick fox' },
    ],
    requestPath: '/solr/testcollection/select?q=' + encodeURIComponent('title:"fox brown quick"') + '&wt=json',
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
      },
    },
  }),
];
