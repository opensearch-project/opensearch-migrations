/**
 * Test cases for Solr Standard Query Parser bare queries → OpenSearch transformation.
 *
 * These tests validate the query-engine's ability to parse and transform
 * Solr's bare term/phrase syntax (without field prefix) into OpenSearch query_string queries.
 *
 * Adding a new test: just add a solrTest() entry below.
 * It automatically runs against every Solr version in matrix.config.ts.
 */
import { solrTest } from '../../../test-types';
import type { TestCase } from '../../../test-types';

export const testCases: TestCase[] = [
  // ───────────────────────────────────────────────────────────
  // Bare queries (no field prefix)
  // ───────────────────────────────────────────────────────────

  solrTest('query-bare-term-with-df', {
    description: 'Bare term with df parameter searches specified field',
    documents: [
      { id: '1', title: 'laptop', description: 'computer' },
      { id: '2', title: 'computer', description: 'laptop' },
      { id: '3', title: 'phone', description: 'device' },
    ],
    requestPath: '/solr/testcollection/select?q=laptop&df=title&wt=json',
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        description: { type: 'text_general' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        description: { type: 'text' },
      },
    },
  }),

  solrTest('query-bare-phrase-with-df', {
    description: 'Bare phrase with df parameter',
    documents: [
      { id: '1', title: 'hello world', description: 'greeting message' },
      { id: '2', title: 'world hello', description: 'hello world' },
      { id: '3', title: 'goodbye world', description: 'farewell' },
    ],
    requestPath: '/solr/testcollection/select?q=' + encodeURIComponent('"hello world"') + '&df=title&wt=json',
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        description: { type: 'text_general' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        description: { type: 'text' },
      },
    },
  }),

  solrTest('query-bare-term-no-match', {
    description: 'Bare term that matches no documents',
    documents: [
      { id: '1', title: 'apple' },
      { id: '2', title: 'banana' },
    ],
    requestPath: '/solr/testcollection/select?q=orange&wt=json',
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
