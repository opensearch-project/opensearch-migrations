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

  // ── Highlighting tests ──────────────────────────────────────────────

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
      { path: '$.highlighting[*][*][*]', rule: 'regex', expected: '.*<em>.*</em>.*', reason: 'Fragment text may differ slightly between highlighters' },
    ],
  }),

  solrTest('highlighting-custom-tags', {
    description: 'Highlighting with custom pre/post tags',
    documents: [
      { id: '1', title: 'Apple iPhone 15 Pro', description: 'Flagship smartphone from Apple' },
      { id: '2', title: 'Samsung Galaxy S24', description: 'Premium smartphone with AI camera' },
    ],
    requestPath: '/solr/testcollection/select?q=description:smartphone&hl=true&hl.fl=description&hl.simple.pre=<b>&hl.simple.post=</b>&fl=id,title&wt=json',
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
      { path: '$.highlighting[*][*][*]', rule: 'regex', expected: '.*<b>.*</b>.*', reason: 'Custom tags should be used' },
    ],
  }),

  solrTest('highlighting-multiple-fields', {
    description: 'Highlighting across multiple fields',
    documents: [
      { id: '1', title: 'Apple MacBook Pro', description: 'Professional laptop from Apple with M3 chip' },
      { id: '2', title: 'Dell XPS Laptop', description: 'Premium ultrabook with Intel processor' },
    ],
    requestPath: '/solr/testcollection/select?q=title:Apple+OR+description:Apple&hl=true&hl.fl=title,description&fl=id,title&wt=json',
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
      { path: '$.highlighting[*][*][*]', rule: 'regex', expected: '.*<em>.*</em>.*', reason: 'Fragment text may differ' },
    ],
  }),

  // ── Terms Component tests ───────────────────────────────────────────

  solrTest('terms-keyword-field', {
    description: 'Terms component on a keyword field — cleanest mapping',
    documents: [
      { id: '1', title: 'Doc A', category: 'electronics' },
      { id: '2', title: 'Doc B', category: 'electronics' },
      { id: '3', title: 'Doc C', category: 'books' },
      { id: '4', title: 'Doc D', category: 'electronics' },
      { id: '5', title: 'Doc E', category: 'books' },
    ],
    requestPath: '/solr/testcollection/terms?terms.fl=category&terms.limit=10&wt=json',
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
      { path: '$.responseHeader.QTime', rule: 'ignore', reason: 'Timing varies' },
      { path: '$.responseHeader.params', rule: 'ignore', reason: 'Param echo differs' },
    ],
  }),

  solrTest('terms-with-sort-index', {
    description: 'Terms component with alphabetical sort',
    documents: [
      { id: '1', title: 'Doc A', category: 'electronics' },
      { id: '2', title: 'Doc B', category: 'books' },
      { id: '3', title: 'Doc C', category: 'clothing' },
    ],
    requestPath: '/solr/testcollection/terms?terms.fl=category&terms.sort=index&terms.limit=10&wt=json',
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
      { path: '$.responseHeader.QTime', rule: 'ignore', reason: 'Timing varies' },
      { path: '$.responseHeader.params', rule: 'ignore', reason: 'Param echo differs' },
    ],
  }),

  solrTest('terms-list-lookup', {
    description: 'Terms component with terms.list for specific term lookup',
    documents: [
      { id: '1', title: 'Doc A', category: 'electronics' },
      { id: '2', title: 'Doc B', category: 'electronics' },
      { id: '3', title: 'Doc C', category: 'books' },
    ],
    requestPath: '/solr/testcollection/terms?terms.fl=category&terms.list=electronics,books,clothing&wt=json',
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
      { path: '$.responseHeader.QTime', rule: 'ignore', reason: 'Timing varies' },
      { path: '$.responseHeader.params', rule: 'ignore', reason: 'Param echo differs' },
    ],
  }),
];
