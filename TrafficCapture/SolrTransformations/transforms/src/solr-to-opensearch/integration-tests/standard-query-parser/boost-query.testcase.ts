/**
 * Test cases for Solr Standard Query Parser boost queries → OpenSearch transformation.
 *
 * These tests validate the query-engine's ability to parse and transform
 * Solr's boost syntax (^N) into OpenSearch queries with boost parameters.
 *
 * Boost only affects scoring/ranking, not which documents match. Therefore,
 * meaningful boost tests require multiple terms where boost affects the
 * relative ordering of results.
 *
 * Solr boost syntax: https://solr.apache.org/guide/solr/latest/query-guide/standard-query-parser.html#boosting-a-term-with
 *
 * Adding a new test: just add a solrTest() entry below.
 * It automatically runs against every Solr version in matrix.config.ts.
 */
import { solrTest } from '../../../test-types';
import type { TestCase } from '../../../test-types';

export const testCases: TestCase[] = [
  // ───────────────────────────────────────────────────────────
  // Multi-term boost queries (where boost affects ranking)
  // ───────────────────────────────────────────────────────────

  solrTest('query-boost-or-terms', {
    description: 'OR query with different boosts affects result ordering',
    documents: [
      { id: '1', title: 'java programming guide' },
      { id: '2', title: 'python programming guide' },
      { id: '3', title: 'java and python together' },
    ],
    requestPath: '/solr/testcollection/select?q=' + encodeURIComponent('title:java^2 OR title:python^1') + '&wt=json',
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

  solrTest('query-boost-or-phrases', {
    description: 'OR query with boosted phrases',
    documents: [
      { id: '1', title: 'hello world greeting' },
      { id: '2', title: 'goodbye world farewell' },
      { id: '3', title: 'hello world goodbye world' },
    ],
    requestPath: '/solr/testcollection/select?q=' + encodeURIComponent('title:"hello world"^2 OR title:"goodbye world"^1') + '&wt=json',
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

  solrTest('query-boost-mixed-term-phrase', {
    description: 'Mixed term and phrase with different boosts',
    documents: [
      { id: '1', title: 'java programming language' },
      { id: '2', title: 'learn java basics' },
      { id: '3', title: 'java programming for beginners' },
    ],
    requestPath: '/solr/testcollection/select?q=' + encodeURIComponent('title:"java programming"^2 OR title:java^1') + '&wt=json',
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

  solrTest('query-boost-decimal', {
    description: 'Decimal boost values in OR query',
    documents: [
      { id: '1', title: 'java tutorial' },
      { id: '2', title: 'python tutorial' },
    ],
    requestPath: '/solr/testcollection/select?q=' + encodeURIComponent('title:java^1.5 OR title:python^0.5') + '&wt=json',
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

  solrTest('query-boost-grouped', {
    description: 'Boosted group combined with another term',
    documents: [
      { id: '1', title: 'java programming', category: 'tech' },
      { id: '2', title: 'python programming', category: 'tech' },
      { id: '3', title: 'cooking recipes', category: 'food' },
    ],
    requestPath: '/solr/testcollection/select?q=' + encodeURIComponent('(title:java OR title:python)^2 OR category:food^1') + '&wt=json',
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        category: { type: 'text_general' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        category: { type: 'text' },
      },
    },
  }),

  solrTest('query-boost-bare-terms', {
    description: 'Bare terms with boosts and df parameter',
    documents: [
      { id: '1', title: 'java programming' },
      { id: '2', title: 'python programming' },
      { id: '3', title: 'java and python' },
    ],
    requestPath: '/solr/testcollection/select?q=' + encodeURIComponent('java^2 OR python^1') + '&df=title&wt=json',
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

  solrTest('query-boost-range-with-term', {
    description: 'Boosted range combined with boosted term',
    documents: [
      { id: '1', title: 'cheap laptop', price: 50 },
      { id: '2', title: 'expensive laptop', price: 500 },
      { id: '3', title: 'mid-range laptop', price: 200 },
    ],
    requestPath: '/solr/testcollection/select?q=' + encodeURIComponent('price:[0 TO 100]^2 OR title:laptop^1') + '&wt=json',
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        price: { type: 'pint' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        price: { type: 'integer' },
      },
    },
  }),
];
