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

  // ───────────────────────────────────────────────────────────
  // Facet tests — JSON Facet API (json.facet)
  // ───────────────────────────────────────────────────────────

  // ───────────────────────────────────────────────────────────
  // Cursor pagination tests — cursorMark → search_after
  // ───────────────────────────────────────────────────────────

  solrTest('cursor-pagination-initial-request', {
    description: 'cursorMark=* should return first page with nextCursorMark',
    documents: [
      { id: '1', title: 'alpha', price: 10 },
      { id: '2', title: 'beta', price: 20 },
      { id: '3', title: 'gamma', price: 30 },
      { id: '4', title: 'delta', price: 40 },
    ],
    requestPath: '/solr/testcollection/select?q=*:*&rows=2&sort=' + encodeURIComponent('price asc,id asc') + '&cursorMark=*&wt=json',
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        price: { type: 'pfloat' },
      },
    },
    opensearchMapping: {
      properties: {
        id: { type: 'keyword' },
        title: { type: 'text' },
        price: { type: 'float' },
      },
    },
    assertionRules: [
      ...SOLR_INTERNAL_RULES,
      { path: '$.nextCursorMark', rule: 'expect-diff', reason: 'Shim uses base64(JSON) encoding vs Solr internal format' },
    ],
  }),

  solrTest('cursor-pagination-full-walk-through', {
    description: 'Walk through all pages using cursorMark — verifies page 2, 3, and end detection',
    documents: [
      { id: '1', title: 'alpha', price: 10 },
      { id: '2', title: 'beta', price: 20 },
      { id: '3', title: 'gamma', price: 30 },
      { id: '4', title: 'delta', price: 40 },
      { id: '5', title: 'epsilon', price: 50 },
    ],
    requestPath: '/solr/testcollection/select?q=*:*&rows=2&sort=' + encodeURIComponent('price asc,id asc') + '&cursorMark=*&wt=json',
    requestSequence: [
      { requestPath: '/solr/testcollection/select?q=*:*&rows=2&sort=' + encodeURIComponent('price asc,id asc') + '&cursorMark={{nextCursorMark}}&wt=json' },
      { requestPath: '/solr/testcollection/select?q=*:*&rows=2&sort=' + encodeURIComponent('price asc,id asc') + '&cursorMark={{nextCursorMark}}&wt=json' },
      { requestPath: '/solr/testcollection/select?q=*:*&rows=2&sort=' + encodeURIComponent('price asc,id asc') + '&cursorMark={{nextCursorMark}}&wt=json' },
    ],
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        price: { type: 'pfloat' },
      },
    },
    opensearchMapping: {
      properties: {
        id: { type: 'keyword' },
        title: { type: 'text' },
        price: { type: 'float' },
      },
    },
    assertionRules: [
      ...SOLR_INTERNAL_RULES,
      { path: '$.nextCursorMark', rule: 'expect-diff', reason: 'Shim uses base64(JSON) encoding vs Solr internal format' },
    ],
  }),

  solrTest('cursor-pagination-descending-sort', {
    description: 'Cursor pagination with descending sort',
    documents: [
      { id: '1', title: 'alpha', price: 10 },
      { id: '2', title: 'beta', price: 20 },
      { id: '3', title: 'gamma', price: 30 },
    ],
    requestPath: '/solr/testcollection/select?q=*:*&rows=2&sort=' + encodeURIComponent('price desc,id asc') + '&cursorMark=*&wt=json',
    requestSequence: [
      { requestPath: '/solr/testcollection/select?q=*:*&rows=2&sort=' + encodeURIComponent('price desc,id asc') + '&cursorMark={{nextCursorMark}}&wt=json' },
    ],
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        price: { type: 'pfloat' },
      },
    },
    opensearchMapping: {
      properties: {
        id: { type: 'keyword' },
        title: { type: 'text' },
        price: { type: 'float' },
      },
    },
    assertionRules: [
      ...SOLR_INTERNAL_RULES,
      { path: '$.nextCursorMark', rule: 'expect-diff', reason: 'Shim uses base64(JSON) encoding vs Solr internal format' },
    ],
  }),

  solrTest('cursor-pagination-default-sort', {
    description: 'cursorMark without explicit sort should default to id asc',
    documents: [
      { id: '1', title: 'alpha' },
      { id: '2', title: 'beta' },
      { id: '3', title: 'gamma' },
    ],
    requestPath: '/solr/testcollection/select?q=*:*&rows=2&sort=' + encodeURIComponent('id asc') + '&cursorMark=*&wt=json',
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
      },
    },
    opensearchMapping: {
      properties: {
        id: { type: 'keyword' },
        title: { type: 'text' },
      },
    },
    assertionRules: [
      ...SOLR_INTERNAL_RULES,
      { path: '$.nextCursorMark', rule: 'expect-diff', reason: 'Shim uses base64(JSON) encoding vs Solr internal format' },
    ],
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

  solrTest('facet-basic-range', {
    description: 'Basic range facet on a numeric field using start/end/gap',
    documents: [
      { id: '1', title: 'cheap item', price: 10 },
      { id: '2', title: 'mid item', price: 35 },
      { id: '3', title: 'pricey item', price: 55 },
      { id: '4', title: 'expensive item', price: 80 },
      { id: '5', title: 'luxury item', price: 95 },
    ],
    requestPath:
      '/solr/testcollection/select?q=*:*&wt=json&json.facet=' +
      encodeURIComponent(
        JSON.stringify({
          prices: { type: 'range', field: 'price', start: 0, end: 100, gap: 20 },
        }),
      ),
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        price: { type: 'pfloat' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        price: { type: 'float' },
      },
    },
    assertionRules: [
      ...SOLR_INTERNAL_RULES,
      {
        path: '$.response',
        rule: 'ignore',
        reason: 'Facet test — only validating $.facets, not hits',
      },
    ],
  }),

  solrTest('facet-arbitrary-range', {
    description: 'Arbitrary range facet with custom bucket boundaries',
    documents: [
      { id: '1', title: 'cheap item', price: 10 },
      { id: '2', title: 'mid item', price: 35 },
      { id: '3', title: 'pricey item', price: 55 },
      { id: '4', title: 'expensive item', price: 80 },
      { id: '5', title: 'luxury item', price: 95 },
    ],
    requestPath:
      '/solr/testcollection/select?q=*:*&wt=json&json.facet=' +
      encodeURIComponent(
        JSON.stringify({
          price_ranges: {
            type: 'range',
            field: 'price',
            ranges: [
              { range: '[0,25)' },
              { range: '[25,50)' },
              { range: '[50,75)' },
              { range: '[75,*)' },
            ],
          },
        }),
      ),
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        price: { type: 'pfloat' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        price: { type: 'float' },
      },
    },
    assertionRules: [
      ...SOLR_INTERNAL_RULES,
      {
        path: '$.response',
        rule: 'ignore',
        reason: 'Facet test — only validating $.facets, not hits',
      },
    ],
  }),

  solrTest('facet-query', {
    description: 'Query facet counting documents matching specific queries',
    documents: [
      { id: '1', title: 'laptop', category: 'electronics', price: 999 },
      { id: '2', title: 'phone', category: 'electronics', price: 699 },
      { id: '3', title: 'shirt', category: 'clothing', price: 29 },
      { id: '4', title: 'pants', category: 'clothing', price: 59 },
      { id: '5', title: 'apple', category: 'food', price: 3 },
      { id: '6', title: 'banana', category: 'food', price: 2 },
    ],
    requestPath:
      '/solr/testcollection/select?q=*:*&wt=json&json.facet=' +
      encodeURIComponent(
        JSON.stringify({
          expensive: { type: 'query', q: 'price:[100 TO *]' },
          cheap: { type: 'query', q: 'price:[* TO 50]' },
          electronics: { type: 'query', q: 'category:electronics' },
        }),
      ),
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        category: { type: 'string' },
        price: { type: 'pfloat' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        category: { type: 'keyword' },
        price: { type: 'float' },
      },
    },
    assertionRules: [
      ...SOLR_INTERNAL_RULES,
      {
        path: '$.response',
        rule: 'ignore',
        reason: 'Facet test — only validating $.facets, not hits',
      },
    ],
  }),

  solrTest('facet-date-range', {
    description: 'Date range facet using start/end/gap with +1MONTH calendar interval',
    documents: [
      { id: '1', title: 'jan event', event_date: '2024-01-15T00:00:00Z' },
      { id: '2', title: 'feb event', event_date: '2024-02-10T00:00:00Z' },
      { id: '3', title: 'mar event', event_date: '2024-03-20T00:00:00Z' },
      { id: '4', title: 'mar event 2', event_date: '2024-03-25T00:00:00Z' },
      { id: '5', title: 'apr event', event_date: '2024-04-05T00:00:00Z' },
    ],
    requestPath:
      '/solr/testcollection/select?q=*:*&wt=json&json.facet=' +
      encodeURIComponent(
        JSON.stringify({
          monthly: {
            type: 'range',
            field: 'event_date',
            start: '2024-01-01T00:00:00Z',
            end: '2024-05-01T00:00:00Z',
            gap: '+1MONTH',
          },
        }),
      ),
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        event_date: { type: 'pdate' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        event_date: { type: 'date' },
      },
    },
    assertionRules: [
      ...SOLR_INTERNAL_RULES,
      {
        path: '$.response',
        rule: 'ignore',
        reason: 'Facet test — only validating $.facets, not hits',
      },
    ],
  }),

  solrTest('facet-numeric-range-with-bounds', {
    description:
      'Numeric range facet where data exists outside the requested start/end — ' +
      'verifies hard_bounds ensures only buckets within [start, end) are returned',
    documents: [
      { id: '1', title: 'very cheap', price: 5 },
      { id: '2', title: 'cheap', price: 15 },
      { id: '3', title: 'mid-low', price: 30 },
      { id: '4', title: 'mid', price: 50 },
      { id: '5', title: 'mid-high', price: 70 },
      { id: '6', title: 'expensive', price: 90 },
      { id: '7', title: 'very expensive', price: 150 },
      { id: '8', title: 'ultra expensive', price: 500 },
    ],
    requestPath:
      '/solr/testcollection/select?q=*:*&wt=json&json.facet=' +
      encodeURIComponent(
        JSON.stringify({
          prices: { type: 'range', field: 'price', start: 20, end: 100, gap: 20 },
        }),
      ),
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        price: { type: 'pfloat' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        price: { type: 'float' },
      },
    },
    assertionRules: [
      ...SOLR_INTERNAL_RULES,
      {
        path: '$.response',
        rule: 'ignore',
        reason: 'Facet test — only validating $.facets, not hits',
      },
    ],
  }),

  solrTest('facet-date-range-with-bounds', {
    description:
      'Date range facet where data exists outside the requested start/end — ' +
      'verifies hard_bounds ensures only buckets within [start, end) are returned',
    documents: [
      { id: '1', title: 'old event', event_date: '2023-06-15T00:00:00Z' },
      { id: '2', title: 'dec event', event_date: '2023-12-20T00:00:00Z' },
      { id: '3', title: 'jan event', event_date: '2024-01-10T00:00:00Z' },
      { id: '4', title: 'feb event', event_date: '2024-02-14T00:00:00Z' },
      { id: '5', title: 'mar event', event_date: '2024-03-01T00:00:00Z' },
      { id: '6', title: 'future event', event_date: '2024-08-20T00:00:00Z' },
      { id: '7', title: 'far future', event_date: '2025-03-01T00:00:00Z' },
    ],
    requestPath:
      '/solr/testcollection/select?q=*:*&wt=json&json.facet=' +
      encodeURIComponent(
        JSON.stringify({
          quarterly: {
            type: 'range',
            field: 'event_date',
            start: '2024-01-01T00:00:00Z',
            end: '2024-04-01T00:00:00Z',
            gap: '+1MONTH',
          },
        }),
      ),
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        event_date: { type: 'pdate' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        event_date: { type: 'date' },
      },
    },
    assertionRules: [
      ...SOLR_INTERNAL_RULES,
      {
        path: '$.response',
        rule: 'ignore',
        reason: 'Facet test — only validating $.facets, not hits',
      },
    ],
  }),

  solrTest('facet-stat-functions', {
    description: 'Stat facet shorthand: avg, sum, min, max, unique, countvals, and count(*) as sub-facets',
    documents: [
      { id: '1', title: 'laptop', category: 'electronics', price: 999, rating: 4 },
      { id: '2', title: 'phone', category: 'electronics', price: 699, rating: 5 },
      { id: '3', title: 'tablet', category: 'electronics', price: 499, rating: 3 },
      { id: '4', title: 'shirt', category: 'clothing', price: 29, rating: 4 },
      { id: '5', title: 'pants', category: 'clothing', price: 59, rating: 2 },
      { id: '6', title: 'apple', category: 'food', price: 3, rating: 5 },
    ],
    requestPath:
      '/solr/testcollection/select?q=*:*&rows=0&wt=json&json.facet=' +
      encodeURIComponent(JSON.stringify({
        avg_price: 'avg(price)',
        total_price: 'sum(price)',
        min_price: 'min(price)',
        max_price: 'max(price)',
        unique_categories: 'unique(category)',
        rated_count: 'countvals(rating)',
        by_category: {
          type: 'terms',
          field: 'category',
          sort: 'count desc',
          facet: {
            avg_price: 'avg(price)',
            max_rating: 'max(rating)',
          },
        },
      })),
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        category: { type: 'string' },
        price: { type: 'pfloat' },
        rating: { type: 'pint' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        category: { type: 'keyword' },
        price: { type: 'float' },
        rating: { type: 'integer' },
      },
    },
    assertionRules: [
      ...SOLR_INTERNAL_RULES,
      { path: '$.response', rule: 'ignore', reason: 'Facet test — only validating $.facets, not hits' },
      { path: '$.terminated_early', rule: 'ignore', reason: 'OpenSearch may return terminated_early with rows=0; Solr does not' },
    ],
  }),

  solrTest('facet-nested-terms-in-terms', {
    description: 'Nested facet: terms facet with a nested terms sub-facet',
    documents: [
      { id: '1', title: 'laptop', category: 'electronics', brand: 'acme' },
      { id: '2', title: 'phone', category: 'electronics', brand: 'acme' },
      { id: '3', title: 'tablet', category: 'electronics', brand: 'globex' },
      { id: '4', title: 'shirt', category: 'clothing', brand: 'acme' },
      { id: '5', title: 'pants', category: 'clothing', brand: 'globex' },
      { id: '6', title: 'apple', category: 'food', brand: 'farms' },
    ],
    requestPath:
      '/solr/testcollection/select?q=*:*&wt=json&json.facet=' +
      encodeURIComponent(JSON.stringify({
        categories: {
          type: 'terms',
          field: 'category',
          sort: 'count desc',
          facet: {
            brands: {
              type: 'terms',
              field: 'brand',
              sort: 'count desc',
            },
          },
        },
      })),
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        category: { type: 'string' },
        brand: { type: 'string' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        category: { type: 'keyword' },
        brand: { type: 'keyword' },
      },
    },
    assertionRules: [
      ...SOLR_INTERNAL_RULES,
      { path: '$.response', rule: 'ignore', reason: 'Facet test — only validating $.facets, not hits' },
    ],
  }),

  // ───────────────────────────────────────────────────────────
  // Highlighting tests
  // ───────────────────────────────────────────────────────────

  solrTest('highlighting-basic', {
    description: 'Basic highlighting with hl=true on a text field',
    documents: [
      { id: '1', title: 'OpenSearch Migrations', description: 'A guide to search migration tools' },
      { id: '2', title: 'Apache Solr', description: 'Enterprise search platform with advanced features' },
      { id: '3', title: 'Elasticsearch Guide', description: 'Full-text search and analytics engine' },
    ],
    requestPath: '/solr/testcollection/select?q=description:search&hl=true&hl.fl=description&fl=id,title&rows=3&wt=json',
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
    assertionRules: [
      ...SOLR_INTERNAL_RULES,
      { path: '$.highlighting[*][*][*]', rule: 'regex', expected: '.*<em>.*</em>.*', reason: 'Solr and OpenSearch both use UnifiedHighlighter but passage scoring differs — fragment text boundaries may not match exactly, so we only verify tags are present' },
    ],
  }),

  solrTest('highlighting-custom-tags', {
    description: 'Highlighting with custom pre/post tags',
    documents: [
      { id: '1', title: 'Apple iPhone 15 Pro', description: 'Flagship smartphone from Apple' },
      { id: '2', title: 'Samsung Galaxy S24', description: 'Premium smartphone with AI camera' },
    ],
    requestPath: '/solr/testcollection/select?q=description:smartphone&hl=true&hl.fl=description&hl.simple.pre=%3Cb%3E&hl.simple.post=%3C%2Fb%3E&fl=id,title&wt=json',
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
    assertionRules: [
      ...SOLR_INTERNAL_RULES,
      { path: '$.highlighting[*][*][*]', rule: 'regex', expected: '.*<b>.*</b>.*', reason: 'Verifies custom pre/post tags (<b></b>) are applied instead of default <em></em> — fragment text may still differ between highlighters' },
    ],
  }),

  solrTest('highlighting-multiple-fields', {
    description: 'Highlighting across multiple fields',
    documents: [
      { id: '1', title: 'Apple MacBook Pro', description: 'Professional laptop from Apple with M3 chip' },
      { id: '2', title: 'Dell XPS Laptop', description: 'Premium ultrabook with Intel processor' },
    ],
    requestPath: '/solr/testcollection/select?q=*:*&hl=true&hl.fl=title,description&hl.q=Apple&fl=id,title&wt=json',
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
    assertionRules: [
      ...SOLR_INTERNAL_RULES,
      { path: '$.highlighting[*][*][*]', rule: 'regex', expected: '.*<em>.*</em>.*', reason: 'Fragment text may differ between Solr and OpenSearch highlighters' },
    ],
  }),

  // ───────────────────────────────────────────────────────────
  // Validation tests — error-path (expectedStatusCode)
  // ───────────────────────────────────────────────────────────

  solrTest('validation-unsupported-param-facet', {
    description: 'Unsupported legacy facet params should return 500',
    documents: [{ id: '1', title: 'test' }],
    requestPath: '/solr/testcollection/select?q=*:*&facet=true&facet.field=title&wt=json',
    expectedStatusCode: 500,
    expectedErrorContains: 'Request transform failed',
  }),

  solrTest('validation-invalid-rows-non-numeric', {
    description: 'Non-numeric rows should return 500',
    documents: [{ id: '1', title: 'test' }],
    requestPath: '/solr/testcollection/select?q=*:*&rows=abc&wt=json',
    expectedStatusCode: 500,
    expectedErrorContains: 'Request transform failed',
  }),

  solrTest('validation-invalid-start-non-numeric', {
    description: 'Non-numeric start should return 500',
    documents: [{ id: '1', title: 'test' }],
    requestPath: '/solr/testcollection/select?q=*:*&start=xyz&wt=json',
    expectedStatusCode: 500,
    expectedErrorContains: 'Request transform failed',
  }),

  solrTest('validation-invalid-hl-boolean', {
    description: 'Invalid boolean value for hl should return 500',
    documents: [{ id: '1', title: 'test' }],
    requestPath: '/solr/testcollection/select?q=*:*&hl=yes&wt=json',
    expectedStatusCode: 500,
    expectedErrorContains: 'Request transform failed',
  }),

  solrTest('validation-local-params-in-q', {
    description: 'Local params syntax in q should return 500',
    documents: [{ id: '1', title: 'test' }],
    requestPath: '/solr/testcollection/select?q=' + encodeURIComponent('{!dismax qf=title}hello') + '&wt=json',
    expectedStatusCode: 500,
    expectedErrorContains: 'Request transform failed',
  }),

  solrTest('validation-invalid-sort-no-direction', {
    description: 'Sort without direction should return 500',
    documents: [{ id: '1', title: 'test' }],
    requestPath: '/solr/testcollection/select?q=*:*&sort=price&wt=json',
    expectedStatusCode: 500,
    expectedErrorContains: 'Request transform failed',
  }),

  solrTest('url-plus-decoding-in-sort', {
    description: 'Sort with + as space (e.g. sort=price+asc) should decode correctly and return sorted results',
    documents: [
      { id: '1', title: 'alpha', price: 30 },
      { id: '2', title: 'beta', price: 10 },
      { id: '3', title: 'gamma', price: 20 },
    ],
    requestPath: '/solr/testcollection/select?q=*:*&sort=price+asc&wt=json',
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        price: { type: 'pfloat' },
      },
    },
    opensearchMapping: {
      properties: {
        id: { type: 'keyword' },
        title: { type: 'text' },
        price: { type: 'float' },
      },
    },
    assertionRules: SOLR_INTERNAL_RULES,
  }),

  solrTest('validation-invalid-json-facet', {
    description: 'Malformed json.facet should return 500',
    documents: [{ id: '1', title: 'test' }],
    requestPath: '/solr/testcollection/select?q=*:*&json.facet=' + encodeURIComponent('{bad json}') + '&wt=json',
    expectedStatusCode: 500,
    expectedErrorContains: 'Request transform failed',
  }),

  solrTest('validation-local-params-in-fl', {
    description: 'Local params syntax in fl should return 500',
    documents: [{ id: '1', title: 'test' }],
    requestPath: '/solr/testcollection/select?q=*:*&fl=' + encodeURIComponent('id,{!func}div(price,2)') + '&wt=json',
    expectedStatusCode: 500,
    expectedErrorContains: 'Request transform failed',
  }),

  solrTest('validation-empty-rows', {
    description: 'Empty rows value should return 500',
    documents: [{ id: '1', title: 'test' }],
    requestPath: '/solr/testcollection/select?q=*:*&rows=&wt=json',
    expectedStatusCode: 500,
    expectedErrorContains: 'Request transform failed',
  }),

  solrTest('validation-empty-start', {
    description: 'Empty start value should return 500',
    documents: [{ id: '1', title: 'test' }],
    requestPath: '/solr/testcollection/select?q=*:*&start=&wt=json',
    expectedStatusCode: 500,
    expectedErrorContains: 'Request transform failed',
  }),

  solrTest('validation-valid-request-passes', {
    description: 'Valid request with supported params should pass through normally',
    documents: [{ id: '1', title: 'test document' }],
    requestPath: '/solr/testcollection/select?q=*:*&rows=10&start=0&fl=id,title&wt=json',
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
