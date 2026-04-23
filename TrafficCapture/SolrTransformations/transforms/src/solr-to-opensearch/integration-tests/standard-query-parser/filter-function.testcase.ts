/**
 * Test cases for Solr's filter(...) inline caching syntax → OpenSearch transformation.
 *
 * The filter() wrapper tells Solr to cache the inner clause and execute it
 * as a constant-score (non-scoring) clause. In OpenSearch, this maps to
 * bool.filter for equivalent non-scoring behavior.
 *
 * Adding a new test: just add a solrTest() entry below.
 * It automatically runs against every Solr version in matrix.config.ts.
 *
 * See: https://solr.apache.org/guide/solr/latest/query-guide/standard-query-parser.html
 */
import { solrTest } from '../../../test-types';
import type { TestCase } from '../../../test-types';

export const testCases: TestCase[] = [
  // ───────────────────────────────────────────────────────────
  // Simple filter expressions
  // ───────────────────────────────────────────────────────────
  solrTest('filter-function-simple', {
    description: 'Simple filter(field:value) wraps clause in bool.filter',
    documents: [
      { id: '1', title: 'laptop', inStock: 'true' },
      { id: '2', title: 'phone', inStock: 'false' },
      { id: '3', title: 'tablet', inStock: 'true' },
    ],
    requestPath: '/solr/testcollection/select?q=filter(inStock:true)&wt=json',
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        inStock: { type: 'text_general' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        inStock: { type: 'text' },
      },
    },
  }),

  solrTest('filter-function-range', {
    description: 'Filter with range query',
    documents: [
      { id: '1', title: 'laptop', price: 500 },
      { id: '2', title: 'phone', price: 50 },
      { id: '3', title: 'tablet', price: 150 },
    ],
    requestPath: '/solr/testcollection/select?q=' + encodeURIComponent('filter(price:[100 TO 1000])') + '&wt=json',
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

  solrTest('filter-function-match-all', {
    description: 'Filter with match all (*:*) is effectively a no-op filter',
    documents: [
      { id: '1', title: 'laptop', category: 'electronics' },
      { id: '2', title: 'phone', category: 'electronics' },
      { id: '3', title: 'shirt', category: 'clothing' },
    ],
    requestPath: '/solr/testcollection/select?q=' + encodeURIComponent('filter(*:*)') + '&wt=json',
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
  // Filter combined with regular queries
  // ───────────────────────────────────────────────────────────
  solrTest('filter-function-with-and', {
    description: 'Filter combined with AND operator',
    documents: [
      { id: '1', title: 'laptop', category: 'electronics', inStock: 'true' },
      { id: '2', title: 'phone', category: 'electronics', inStock: 'false' },
      { id: '3', title: 'shirt', category: 'clothing', inStock: 'true' },
    ],
    requestPath: '/solr/testcollection/select?q=' + encodeURIComponent('category:electronics AND filter(inStock:true)') + '&wt=json',
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        category: { type: 'text_general' },
        inStock: { type: 'text_general' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        category: { type: 'text' },
        inStock: { type: 'text' },
      },
    },
  }),

  solrTest('filter-function-with-or', {
    description: 'Filter combined with OR operator',
    documents: [
      { id: '1', title: 'laptop', category: 'electronics', inStock: 'true' },
      { id: '2', title: 'phone', category: 'electronics', inStock: 'false' },
      { id: '3', title: 'shirt', category: 'clothing', inStock: 'true' },
    ],
    requestPath: '/solr/testcollection/select?q=' + encodeURIComponent('category:clothing OR filter(inStock:true)') + '&wt=json',
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        category: { type: 'text_general' },
        inStock: { type: 'text_general' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        category: { type: 'text' },
        inStock: { type: 'text' },
      },
    },
  }),

  solrTest('filter-function-mixed-precedence', {
    description: 'Mixed filter() and non-filter clauses with operator precedence',
    documents: [
      { id: '1', title: 'laptop', category: 'electronics', status: 'active', inStock: 'true' },
      { id: '2', title: 'phone', category: 'electronics', status: 'inactive', inStock: 'true' },
      { id: '3', title: 'shirt', category: 'clothing', status: 'active', inStock: 'false' },
      { id: '4', title: 'tablet', category: 'electronics', status: 'active', inStock: 'false' },
    ],
    // Tests: category:electronics AND (status:active OR filter(inStock:true))
    // Should match: id 1 (electronics, active, inStock), id 2 (electronics, inactive but inStock), id 4 (electronics, active)
    requestPath: '/solr/testcollection/select?q=' + encodeURIComponent('category:electronics AND (status:active OR filter(inStock:true))') + '&wt=json',
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        category: { type: 'text_general' },
        status: { type: 'text_general' },
        inStock: { type: 'text_general' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        category: { type: 'text' },
        status: { type: 'text' },
        inStock: { type: 'text' },
      },
    },
  }),

  // ───────────────────────────────────────────────────────────
  // Multiple filter clauses
  // ───────────────────────────────────────────────────────────
  solrTest('filter-function-multiple', {
    description: 'Multiple filter() clauses with AND in same query',
    documents: [
      { id: '1', title: 'laptop', inStock: 'true', price: 500 },
      { id: '2', title: 'phone', inStock: 'true', price: 50 },
      { id: '3', title: 'tablet', inStock: 'false', price: 150 },
    ],
    requestPath: '/solr/testcollection/select?q=' + encodeURIComponent('filter(inStock:true) AND filter(price:[100 TO 1000])') + '&wt=json',
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        inStock: { type: 'text_general' },
        price: { type: 'pint' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        inStock: { type: 'text' },
        price: { type: 'integer' },
      },
    },
  }),

  solrTest('filter-function-multiple-or', {
    description: 'Multiple filter() clauses with OR in same query',
    documents: [
      { id: '1', title: 'laptop', category: 'electronics', status: 'active' },
      { id: '2', title: 'phone', category: 'electronics', status: 'inactive' },
      { id: '3', title: 'shirt', category: 'clothing', status: 'active' },
      { id: '4', title: 'book', category: 'books', status: 'inactive' },
    ],
    requestPath: '/solr/testcollection/select?q=' + encodeURIComponent('filter(category:electronics) OR filter(category:clothing)') + '&wt=json',
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
  // Filter with boolean expressions inside
  // ───────────────────────────────────────────────────────────
  solrTest('filter-function-nested-boolean', {
    description: 'Filter with nested boolean expression',
    documents: [
      { id: '1', title: 'laptop', status: 'active', type: 'electronics' },
      { id: '2', title: 'phone', status: 'inactive', type: 'electronics' },
      { id: '3', title: 'shirt', status: 'active', type: 'clothing' },
    ],
    requestPath: '/solr/testcollection/select?q=' + encodeURIComponent('filter(status:active AND type:electronics)') + '&wt=json',
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        status: { type: 'text_general' },
        type: { type: 'text_general' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        status: { type: 'text' },
        type: { type: 'text' },
      },
    },
  }),

  // ───────────────────────────────────────────────────────────
  // Filter with boost
  // ───────────────────────────────────────────────────────────
  solrTest('filter-function-boost-inner', {
    description: 'Filter with boost on inner clause',
    documents: [
      { id: '1', title: 'laptop', category: 'software' },
      { id: '2', title: 'phone', category: 'hardware' },
    ],
    requestPath: '/solr/testcollection/select?q=' + encodeURIComponent('filter(category:software^2)') + '&wt=json',
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

  solrTest('filter-function-boost-wrapper', {
    description: 'Filter with boost on filter wrapper',
    documents: [
      { id: '1', title: 'laptop', category: 'software' },
      { id: '2', title: 'phone', category: 'hardware' },
    ],
    requestPath: '/solr/testcollection/select?q=' + encodeURIComponent('filter(category:software)^2') + '&wt=json',
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
  // Negated filter expressions
  // ───────────────────────────────────────────────────────────
  solrTest('filter-function-negated-minus', {
    description: 'Negated filter using minus prefix excludes matching documents',
    documents: [
      { id: '1', title: 'laptop', inStock: 'true' },
      { id: '2', title: 'phone', inStock: 'false' },
      { id: '3', title: 'tablet', inStock: 'true' },
    ],
    requestPath: '/solr/testcollection/select?q=' + encodeURIComponent('*:* -filter(inStock:false)') + '&q.op=AND&wt=json',
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        inStock: { type: 'text_general' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        inStock: { type: 'text' },
      },
    },
  }),

  solrTest('filter-function-negated-not', {
    description: 'Negated filter using NOT operator excludes matching documents',
    documents: [
      { id: '1', title: 'laptop', status: 'active' },
      { id: '2', title: 'phone', status: 'inactive' },
      { id: '3', title: 'tablet', status: 'active' },
    ],
    requestPath: '/solr/testcollection/select?q=' + encodeURIComponent('*:* AND NOT filter(status:inactive)') + '&wt=json',
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

  solrTest('filter-function-negated-inner', {
    description: 'Filter with negation inside the filter clause (requires positive clause)',
    documents: [
      { id: '1', title: 'laptop', status: 'active' },
      { id: '2', title: 'phone', status: 'inactive' },
      { id: '3', title: 'tablet', status: 'discontinued' },
    ],
    requestPath: '/solr/testcollection/select?q=' + encodeURIComponent('filter(*:* AND -status:inactive)') + '&wt=json',
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
