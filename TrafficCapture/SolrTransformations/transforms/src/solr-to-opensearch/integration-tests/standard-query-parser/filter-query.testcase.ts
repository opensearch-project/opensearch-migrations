/**
 * Test cases for Solr fq (filter query) parameter → OpenSearch transformation.
 *
 * These tests validate the transformation of Solr's fq parameter into
 * OpenSearch's bool.filter clause. Filter queries restrict results without
 * affecting scores, and multiple fq params are AND'd together.
 *
 * Adding a new test: just add a solrTest() entry below.
 * It automatically runs against every Solr version in matrix.config.ts.
 */
import { solrTest } from '../../../test-types';
import type { TestCase } from '../../../test-types';

export const testCases: TestCase[] = [
  // ───────────────────────────────────────────────────────────
  // Single fq parameter
  // ───────────────────────────────────────────────────────────

  solrTest('filter-query-single-field', {
    description: 'Single fq parameter filters results without affecting score',
    documents: [
      { id: '1', title: 'laptop', category: 'electronics', status: 'active' },
      { id: '2', title: 'phone', category: 'electronics', status: 'inactive' },
      { id: '3', title: 'shirt', category: 'clothing', status: 'active' },
    ],
    requestPath: '/solr/testcollection/select?q=*:*&fq=status:active&wt=json',
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        category: { type: 'text_general' },
        status: { type: 'text_general' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        category: { type: 'text' },
        status: { type: 'text' },
      },
    },
  }),

  solrTest('filter-query-with-main-query', {
    description: 'fq combined with q parameter',
    documents: [
      { id: '1', title: 'gaming laptop', category: 'electronics', status: 'active' },
      { id: '2', title: 'business laptop', category: 'electronics', status: 'inactive' },
      { id: '3', title: 'gaming console', category: 'electronics', status: 'active' },
    ],
    requestPath: '/solr/testcollection/select?q=title:laptop&fq=status:active&wt=json',
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        category: { type: 'text_general' },
        status: { type: 'text_general' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        category: { type: 'text' },
        status: { type: 'text' },
      },
    },
  }),

  // ───────────────────────────────────────────────────────────
  // Multiple fq parameters (AND'd together)
  // ───────────────────────────────────────────────────────────

  solrTest('filter-query-multiple-fq', {
    description: 'Multiple fq parameters are AND\'d together',
    documents: [
      { id: '1', title: 'laptop', category: 'electronics', status: 'active', inStock: true },
      { id: '2', title: 'phone', category: 'electronics', status: 'active', inStock: false },
      { id: '3', title: 'tablet', category: 'electronics', status: 'inactive', inStock: true },
      { id: '4', title: 'shirt', category: 'clothing', status: 'active', inStock: true },
    ],
    requestPath: '/solr/testcollection/select?q=*:*&fq=status:active&fq=category:electronics&wt=json',
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        category: { type: 'text_general' },
        status: { type: 'text_general' },
        inStock: { type: 'boolean' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        category: { type: 'text' },
        status: { type: 'text' },
        inStock: { type: 'boolean' },
      },
    },
  }),

  // ───────────────────────────────────────────────────────────
  // Range filter queries
  // ───────────────────────────────────────────────────────────

  solrTest('filter-query-range', {
    description: 'Range query in fq parameter',
    documents: [
      { id: '1', title: 'cheap item', price: 10 },
      { id: '2', title: 'mid item', price: 50 },
      { id: '3', title: 'expensive item', price: 100 },
      { id: '4', title: 'luxury item', price: 500 },
    ],
    requestPath: '/solr/testcollection/select?q=*:*&fq=' + encodeURIComponent('price:[10 TO 100]') + '&wt=json',
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

  solrTest('filter-query-range-with-main-query', {
    description: 'Range fq combined with text query',
    documents: [
      { id: '1', title: 'cheap laptop', price: 200 },
      { id: '2', title: 'expensive laptop', price: 2000 },
      { id: '3', title: 'cheap phone', price: 100 },
      { id: '4', title: 'expensive phone', price: 1500 },
    ],
    requestPath: '/solr/testcollection/select?q=title:laptop&fq=' + encodeURIComponent('price:[0 TO 500]') + '&wt=json',
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

  // ───────────────────────────────────────────────────────────
  // Boolean expressions in fq
  // ───────────────────────────────────────────────────────────

  solrTest('filter-query-boolean-and', {
    description: 'Boolean AND expression in single fq',
    documents: [
      { id: '1', title: 'item1', category: 'electronics', status: 'active' },
      { id: '2', title: 'item2', category: 'electronics', status: 'inactive' },
      { id: '3', title: 'item3', category: 'clothing', status: 'active' },
    ],
    requestPath: '/solr/testcollection/select?q=*:*&fq=' + encodeURIComponent('category:electronics AND status:active') + '&wt=json',
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        category: { type: 'text_general' },
        status: { type: 'text_general' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        category: { type: 'text' },
        status: { type: 'text' },
      },
    },
  }),

  solrTest('filter-query-boolean-or', {
    description: 'Boolean OR expression in fq',
    documents: [
      { id: '1', title: 'laptop', category: 'electronics' },
      { id: '2', title: 'phone', category: 'electronics' },
      { id: '3', title: 'shirt', category: 'clothing' },
      { id: '4', title: 'book', category: 'books' },
    ],
    requestPath: '/solr/testcollection/select?q=*:*&fq=' + encodeURIComponent('category:electronics OR category:clothing') + '&wt=json',
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

  // ───────────────────────────────────────────────────────────
  // Phrase filter queries
  // ───────────────────────────────────────────────────────────

  solrTest('filter-query-phrase', {
    description: 'Phrase query in fq parameter',
    documents: [
      { id: '1', title: 'hello world', category: 'greeting' },
      { id: '2', title: 'world hello', category: 'greeting' },
      { id: '3', title: 'hello there world', category: 'greeting' },
    ],
    requestPath: '/solr/testcollection/select?q=*:*&fq=' + encodeURIComponent('title:"hello world"') + '&wt=json',
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

  // ───────────────────────────────────────────────────────────
  // Match all filter (*:*)
  // ───────────────────────────────────────────────────────────

  solrTest('filter-query-match-all', {
    description: 'Match all (*:*) in fq is effectively a no-op filter',
    documents: [
      { id: '1', title: 'item1' },
      { id: '2', title: 'item2' },
      { id: '3', title: 'item3' },
    ],
    requestPath: '/solr/testcollection/select?q=title:item1&fq=*:*&wt=json',
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

  // ───────────────────────────────────────────────────────────
  // Complex combined scenarios
  // ───────────────────────────────────────────────────────────

  solrTest('filter-query-complex-combined', {
    description: 'Complex query with multiple fq including range and field filters',
    documents: [
      { id: '1', title: 'gaming laptop', category: 'electronics', price: 1000, status: 'active' },
      { id: '2', title: 'business laptop', category: 'electronics', price: 800, status: 'active' },
      { id: '3', title: 'gaming laptop', category: 'electronics', price: 2000, status: 'inactive' },
      { id: '4', title: 'gaming console', category: 'electronics', price: 500, status: 'active' },
      { id: '5', title: 'gaming shirt', category: 'clothing', price: 50, status: 'active' },
    ],
    requestPath: '/solr/testcollection/select?q=title:gaming&fq=category:electronics&fq=' + encodeURIComponent('price:[0 TO 1500]') + '&fq=status:active&wt=json',
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        category: { type: 'text_general' },
        price: { type: 'pint' },
        status: { type: 'text_general' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        category: { type: 'text' },
        price: { type: 'integer' },
        status: { type: 'text' },
      },
    },
  }),

  // ───────────────────────────────────────────────────────────
  // filter() inline caching syntax
  // ───────────────────────────────────────────────────────────

  solrTest('filter-query-filter-function', {
    description: 'filter() syntax in fq for individual clause caching',
    documents: [
      { id: '1', title: 'laptop', category: 'electronics', inStock: true },
      { id: '2', title: 'phone', category: 'electronics', inStock: false },
      { id: '3', title: 'tablet', category: 'electronics', inStock: true },
    ],
    requestPath: '/solr/testcollection/select?q=*:*&fq=' + encodeURIComponent('filter(inStock:true)') + '&wt=json',
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        category: { type: 'text_general' },
        inStock: { type: 'boolean' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        category: { type: 'text' },
        inStock: { type: 'boolean' },
      },
    },
  }),

  // ───────────────────────────────────────────────────────────
  // Empty fq (no-op)
  // ───────────────────────────────────────────────────────────

  solrTest('filter-query-empty-fq', {
    description: 'Empty fq parameter does not alter results',
    documents: [
      { id: '1', title: 'laptop', category: 'electronics' },
      { id: '2', title: 'phone', category: 'electronics' },
      { id: '3', title: 'shirt', category: 'clothing' },
    ],
    requestPath: '/solr/testcollection/select?q=*:*&fq=&wt=json',
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

  // ───────────────────────────────────────────────────────────
  // Negation in fq
  // ───────────────────────────────────────────────────────────

  solrTest('filter-query-negation-not', {
    description: 'NOT operator in fq excludes matching documents',
    documents: [
      { id: '1', title: 'laptop', status: 'active' },
      { id: '2', title: 'phone', status: 'inactive' },
      { id: '3', title: 'tablet', status: 'active' },
      { id: '4', title: 'monitor', status: 'discontinued' },
    ],
    requestPath: '/solr/testcollection/select?q=*:*&fq=' + encodeURIComponent('NOT status:inactive') + '&wt=json',
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        status: { type: 'text_general' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        status: { type: 'text' },
      },
    },
  }),

  solrTest('filter-query-negation-minus', {
    description: 'Minus prefix operator in fq excludes matching documents',
    documents: [
      { id: '1', title: 'laptop', status: 'active' },
      { id: '2', title: 'phone', status: 'inactive' },
      { id: '3', title: 'tablet', status: 'active' },
      { id: '4', title: 'monitor', status: 'discontinued' },
    ],
    requestPath: '/solr/testcollection/select?q=*:*&fq=-status:inactive&wt=json',
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        status: { type: 'text_general' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        status: { type: 'text' },
      },
    },
  }),
];
