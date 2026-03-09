/**
 * Test cases for the Solr → OpenSearch transformation.
 *
 * Adding a new test: just add a solrTest() entry below.
 * It automatically runs against every Solr version in matrix.config.ts.
 *
 * Each test case defines:
 * - solrSchema: the Solr collection's field types (applied via Schema API)
 * - opensearchMapping: the corresponding OpenSearch index mapping
 * - documents: data seeded into both backends
 * - requestPath: the Solr query to test
 * - assertionRules: expected differences from Solr (everything else must match exactly)
 */
import { solrTest } from '../test-types';
import type { TestCase } from '../test-types';

export const testCases: TestCase[] = [
  solrTest('basic-select-compare-with-solr', {
    documents: [{ id: '1', title: 'test document', content: 'hello world' }],
    requestPath: '/solr/testcollection/select?q=*:*&wt=json',
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        content: { type: 'text_general' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        content: { type: 'text' },
      },
    },
  }),

  solrTest('multiple-documents-compare-with-solr', {
    documents: [
      { id: '1', title: 'first doc', content: 'alpha' },
      { id: '2', title: 'second doc', content: 'beta' },
      { id: '3', title: 'third doc', content: 'gamma' },
    ],
    requestPath: '/solr/testcollection/select?q=*:*&wt=json',
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        content: { type: 'text_general' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        content: { type: 'text' },
      },
    },
  }),

  solrTest('field-query-compare-with-solr', {
    documents: [
      { id: '1', title: 'java programming', author: 'smith' },
      { id: '2', title: 'python scripting', author: 'jones' },
    ],
    requestPath: '/solr/testcollection/select?q=title:java&wt=json',
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        author: { type: 'text_general' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        author: { type: 'text' },
      },
    },
  }),

  solrTest('boolean-and-compare-with-solr', {
    documents: [
      { id: '1', title: 'java programming', author: 'smith' },
      { id: '2', title: 'java basics', author: 'jones' },
      { id: '3', title: 'python scripting', author: 'smith' },
    ],
    requestPath: '/solr/testcollection/select?q=title:java AND author:smith&wt=json',
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        author: { type: 'text_general' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        author: { type: 'text' },
      },
    },
  }),

  solrTest('boolean-or-compare-with-solr', {
    documents: [
      { id: '1', title: 'java programming', author: 'smith' },
      { id: '2', title: 'python scripting', author: 'jones' },
      { id: '3', title: 'ruby gems', author: 'doe' },
    ],
    requestPath: '/solr/testcollection/select?q=title:java OR title:python&wt=json',
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        author: { type: 'text_general' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        author: { type: 'text' },
      },
    },
  }),

  solrTest('phrase-query-compare-with-solr', {
    documents: [
      { id: '1', title: 'hello world', content: 'greeting' },
      { id: '2', title: 'world hello', content: 'reversed' },
    ],
    requestPath: '/solr/testcollection/select?q="hello world"&wt=json',
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        content: { type: 'text_general' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        content: { type: 'text' },
      },
    },
  }),

  solrTest('range-query-compare-with-solr', {
    documents: [
      { id: '1', title: 'cheap item', price: 5 },
      { id: '2', title: 'mid item', price: 50 },
      { id: '3', title: 'expensive item', price: 200 },
    ],
    requestPath: '/solr/testcollection/select?q=price:[10 TO 100]&wt=json',
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

  solrTest('boost-query-compare-with-solr', {
    documents: [
      { id: '1', title: 'java programming', content: 'intro' },
      { id: '2', title: 'python scripting', content: 'java' },
    ],
    requestPath: '/solr/testcollection/select?q=title:java^2&wt=json',
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        content: { type: 'text_general' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        content: { type: 'text' },
      },
    },
  }),

  solrTest('filter-query-compare-with-solr', {
    documents: [
      { id: '1', title: 'active doc', status: 'active' },
      { id: '2', title: 'inactive doc', status: 'inactive' },
    ],
    requestPath: '/solr/testcollection/select?q=*:*&fq=status:active&wt=json',
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        status: { type: 'string' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        status: { type: 'keyword' },
      },
    },
  }),

  solrTest('sort-compare-with-solr', {
    documents: [
      { id: '1', title: 'alpha', price: 30 },
      { id: '2', title: 'beta', price: 10 },
      { id: '3', title: 'gamma', price: 20 },
    ],
    requestPath: '/solr/testcollection/select?q=*:*&sort=price asc&wt=json',
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

  solrTest('pagination-compare-with-solr', {
    documents: [
      { id: '1', title: 'doc one' },
      { id: '2', title: 'doc two' },
      { id: '3', title: 'doc three' },
      { id: '4', title: 'doc four' },
      { id: '5', title: 'doc five' },
    ],
    requestPath: '/solr/testcollection/select?q=*:*&start=10&rows=20&wt=json',
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
