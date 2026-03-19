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
import { solrTest, SOLR_INTERNAL_RULES } from '../test-types';
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

  solrTest('rows-limits-returned-documents', {
    documents: [
      { id: '1', title: 'first doc', content: 'alpha' },
      { id: '2', title: 'second doc', content: 'beta' },
      { id: '3', title: 'third doc', content: 'gamma' },
    ],
    requestPath: '/solr/testcollection/select?q=*:*&rows=2&wt=json',
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

  solrTest('rows-with-start-pagination', {
    documents: [
      { id: '1', title: 'first doc', content: 'alpha' },
      { id: '2', title: 'second doc', content: 'beta' },
      { id: '3', title: 'third doc', content: 'gamma' },
      { id: '4', title: 'fourth doc', content: 'delta' },
    ],
    requestPath: '/solr/testcollection/select?q=*:*&rows=2&start=2&wt=json',
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

  // ───────────────────────────────────────────────────────────
  // Facet tests — JSON Facet API (json.facet)
  // ───────────────────────────────────────────────────────────

  solrTest('facet-basic-terms', {
    description: 'Basic terms facet on a keyword field with distinct counts',
    documents: [
      { id: '1', title: 'laptop', category: 'electronics' },
      { id: '2', title: 'phone', category: 'electronics' },
      { id: '3', title: 'phone case', category: 'electronics' },
      { id: '4', title: 'shirt', category: 'clothing' },
      { id: '5', title: 'pants', category: 'clothing' },
      { id: '6', title: 'apple', category: 'food' },
    ],
    requestPath:
      '/solr/testcollection/select?q=*:*&wt=json&json.facet=' +
      encodeURIComponent(JSON.stringify({
        categories: { type: 'terms', field: 'category', sort: 'count desc' },
      })),
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        category: { type: 'string' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        category: { type: 'keyword' },
      },
    },
    assertionRules: [
      ...SOLR_INTERNAL_RULES,
      { path: '$.response', rule: 'ignore', reason: 'Facet test — only validating $.facets, not hits' },
    ],
  }),

  solrTest('facet-terms-with-offset-and-limit', {
    description: 'Terms facet with offset and limit to verify size = offset + limit conversion',
    documents: [
      { id: '1', title: 'laptop', category: 'electronics' },
      { id: '2', title: 'phone', category: 'electronics' },
      { id: '3', title: 'phone case', category: 'electronics' },
      { id: '4', title: 'shirt', category: 'clothing' },
      { id: '5', title: 'pants', category: 'clothing' },
      { id: '6', title: 'apple', category: 'food' },
      { id: '7', title: 'banana', category: 'food' },
      { id: '8', title: 'hammer', category: 'tools' },
    ],
    requestPath:
      '/solr/testcollection/select?q=*:*&wt=json&json.facet=' +
      encodeURIComponent(JSON.stringify({
        categories: { type: 'terms', field: 'category', offset: 1, limit: 2, sort: 'count desc' },
      })),
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        category: { type: 'string' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        category: { type: 'keyword' },
      },
    },
    assertionRules: [
      ...SOLR_INTERNAL_RULES,
      { path: '$.response', rule: 'ignore', reason: 'Facet test — only validating $.facets, not hits' },
      {
        path: '$.facets.categories.buckets',
        rule: 'sublist',
        skip: 1,
        reason:
          'OpenSearch has no native offset for terms aggs — proxy returns size=offset+limit buckets. ' +
          'The last `limit` buckets (after skipping the first 1) must match Solr exactly.',
      },
    ],
  }),

  // ───────────────────────────────────────────────────────────
  // Field list (fl) tests — _source filtering
  // ───────────────────────────────────────────────────────────

  solrTest('field-list-param', {
    description: 'fl parameter with mixed comma/space separators and glob pattern (na*)',
    documents: [
      { id: '1', name: 'Alice', name_full: 'Alice Smith', price: 100 },
      { id: '2', name: 'Bob', name_full: 'Bob Jones', price: 200 },
    ],
    requestPath: '/solr/testcollection/select?q=*:*&fl=id,na*%20price&wt=json',
    solrSchema: {
      fields: {
        name: { type: 'text_general' },
        name_full: { type: 'text_general' },
        price: { type: 'pint' },
      },
    },
    opensearchMapping: {
      properties: {
        name: { type: 'text' },
        name_full: { type: 'text' },
        price: { type: 'integer' },
      },
    },
  }),

  // ───────────────────────────────────────────────────────────
  // Query parser tests — Standard Query Parser (lucene syntax)
  // ───────────────────────────────────────────────────────────

  solrTest('query-term-single-field', {
    description: 'Simple term query on a keyword field',
    documents: [
      { id: '1', title: 'laptop', category: 'electronics' },
      { id: '2', title: 'phone', category: 'electronics' },
      { id: '3', title: 'shirt', category: 'clothing' },
    ],
    requestPath: '/solr/testcollection/select?q=category:electronics&wt=json',
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

  solrTest('query-range-inclusive-exclusive', {
    description: 'Range queries with inclusive and exclusive bounds',
    documents: [
      { id: '1', title: 'cheap low stock', price: 10, stock: 5 },
      { id: '2', title: 'mid good stock', price: 50, stock: 25 },
      { id: '3', title: 'expensive high stock', price: 100, stock: 50 },
      { id: '4', title: 'luxury item', price: 500, stock: 10 },
      { id: '5', title: 'free item', price: 0, stock: 100 },
    ],
    // price:[10 TO 100] AND stock:{0 TO 50}
    requestPath: '/solr/testcollection/select?q=' + encodeURIComponent('price:[10 TO 100] AND stock:{0 TO 50}') + '&wt=json',
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        price: { type: 'pint' },
        stock: { type: 'pint' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        price: { type: 'integer' },
        stock: { type: 'integer' },
      },
    },
  }),

  solrTest('query-boolean-operators', {
    description: 'Combined AND, OR, NOT boolean operators',
    // Query: (category:electronics OR category:clothing) AND brand:nike NOT status:discontinued
    documents: [
      { id: '1', title: 'laptop', category: 'electronics', brand: 'apple', status: 'active' },
      { id: '2', title: 'phone', category: 'electronics', brand: 'samsung', status: 'active' },
      { id: '3', title: 'tablet', category: 'electronics', brand: 'nike', status: 'discontinued' },
      { id: '4', title: 'shirt', category: 'clothing', brand: 'nike', status: 'active' },
      { id: '5', title: 'shoes', category: 'electronics', brand: 'nike', status: 'active' },
      { id: '6', title: 'apple', category: 'food', brand: 'organic', status: 'active' },
    ],
    requestPath: '/solr/testcollection/select?q=' + encodeURIComponent('(category:electronics OR category:clothing) AND brand:nike NOT status:discontinued') + '&wt=json',
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        category: { type: 'text_general' },
        brand: { type: 'text_general' },
        status: { type: 'text_general' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        category: { type: 'text' },
        brand: { type: 'text' },
        status: { type: 'text' },
      },
    },
  }),

  solrTest('query-phrase', {
    description: 'Phrase query matching exact sequence',
    documents: [
      { id: '1', title: 'hello world', content: 'greeting message' },
      { id: '2', title: 'world hello', content: 'reversed greeting' },
      { id: '3', title: 'hello there world', content: 'split greeting' },
    ],
    requestPath: '/solr/testcollection/select?q=' + encodeURIComponent('title:"hello world"') + '&wt=json',
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

  solrTest('query-prefix-operators', {
    description: 'Required (+) and prohibited (-) prefix operators',
    documents: [
      { id: '1', title: 'laptop', category: 'electronics', status: 'active' },
      { id: '2', title: 'phone', category: 'electronics', status: 'discontinued' },
      { id: '3', title: 'shirt', category: 'clothing', status: 'active' },
    ],
    requestPath: '/solr/testcollection/select?q=' + encodeURIComponent('+category:electronics -status:discontinued') + '&wt=json',
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

  solrTest('query-boost', {
    description: 'Boosted term query to influence relevance scoring',
    documents: [
      { id: '1', title: 'laptop computer', category: 'electronics' },
      { id: '2', title: 'laptop bag', category: 'accessories' },
      { id: '3', title: 'computer desk', category: 'furniture' },
    ],
    requestPath: '/solr/testcollection/select?q=' + encodeURIComponent('title:laptop^2 OR title:computer') + '&wt=json',
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
];