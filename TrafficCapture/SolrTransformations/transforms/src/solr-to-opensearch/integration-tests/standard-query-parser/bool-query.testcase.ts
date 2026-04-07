/**
 * Test cases for Solr Standard Query Parser boolean queries → OpenSearch transformation.
 *
 * These tests validate the query-engine's ability to parse and transform
 * Solr's boolean operators (AND/OR/NOT, &&/||/!, +/-) into OpenSearch bool queries.
 *
 * Adding a new test: just add a solrTest() entry below.
 * It automatically runs against every Solr version in matrix.config.ts.
 */
import { solrTest } from '../../../test-types';
import type { TestCase, SolrSchema, OpenSearchMapping } from '../../../test-types';

// Shared schema/mapping for most boolean tests
const threeFieldSchema: SolrSchema = {
  fields: {
    title: { type: 'text_general' },
    category: { type: 'text_general' },
    status: { type: 'text_general' },
  },
};
const threeFieldMapping: OpenSearchMapping = {
  properties: {
    title: { type: 'text' },
    category: { type: 'text' },
    status: { type: 'text' },
  },
};

// Common document sets
const electronicsClothingDocs = [
  { id: '1', title: 'laptop', category: 'electronics', status: 'active' },
  { id: '2', title: 'phone', category: 'electronics', status: 'draft' },
  { id: '3', title: 'tablet', category: 'electronics', status: 'active' },
  { id: '4', title: 'shirt', category: 'clothing', status: 'active' },
  { id: '5', title: 'hammer', category: 'tools', status: 'active' },
];

export const testCases: TestCase[] = [
  // ───────────────────────────────────────────────────────────
  // Basic boolean operators (AND, OR, NOT)
  // ───────────────────────────────────────────────────────────

  solrTest('query-bool-and', {
    description: 'AND query requires both clauses to match',
    documents: electronicsClothingDocs,
    requestPath: '/solr/testcollection/select?q=' + encodeURIComponent('category:electronics AND status:active') + '&wt=json',
    solrSchema: threeFieldSchema,
    opensearchMapping: threeFieldMapping,
  }),

  solrTest('query-bool-or', {
    description: 'OR query matches either clause',
    documents: electronicsClothingDocs,
    requestPath: '/solr/testcollection/select?q=' + encodeURIComponent('category:electronics OR category:clothing') + '&wt=json',
    solrSchema: threeFieldSchema,
    opensearchMapping: threeFieldMapping,
  }),

  solrTest('query-bool-not', {
    description: 'NOT query excludes matching documents',
    documents: electronicsClothingDocs,
    requestPath: '/solr/testcollection/select?q=' + encodeURIComponent('category:electronics AND NOT status:draft') + '&wt=json',
    solrSchema: threeFieldSchema,
    opensearchMapping: threeFieldMapping,
  }),

  solrTest('query-bool-pure-not', {
    description: 'Pure NOT query (everything except matching)',
    documents: electronicsClothingDocs,
    requestPath: '/solr/testcollection/select?q=' + encodeURIComponent('NOT status:draft') + '&wt=json',
    solrSchema: threeFieldSchema,
    opensearchMapping: threeFieldMapping,
  }),

  solrTest('query-bool-implicit-or', {
    description: 'Implicit OR with adjacent terms (no operator)',
    documents: electronicsClothingDocs,
    requestPath: '/solr/testcollection/select?q=' + encodeURIComponent('category:electronics category:clothing') + '&wt=json',
    solrSchema: threeFieldSchema,
    opensearchMapping: threeFieldMapping,
  }),

  solrTest('query-bool-combined', {
    description: 'Combined AND, OR, NOT with grouping',
    documents: electronicsClothingDocs,
    requestPath: '/solr/testcollection/select?q=' + encodeURIComponent('(category:electronics OR category:clothing) AND NOT status:draft') + '&wt=json',
    solrSchema: threeFieldSchema,
    opensearchMapping: threeFieldMapping,
  }),

  // ───────────────────────────────────────────────────────────
  // Alternative operators (&&, ||, !)
  // ───────────────────────────────────────────────────────────

  solrTest('query-bool-ampersand', {
    description: '&& operator as AND alternative',
    documents: electronicsClothingDocs,
    requestPath: '/solr/testcollection/select?q=' + encodeURIComponent('category:electronics && status:active') + '&wt=json',
    solrSchema: threeFieldSchema,
    opensearchMapping: threeFieldMapping,
  }),

  solrTest('query-bool-pipe', {
    description: '|| operator as OR alternative',
    documents: electronicsClothingDocs,
    requestPath: '/solr/testcollection/select?q=' + encodeURIComponent('category:electronics || category:clothing') + '&wt=json',
    solrSchema: threeFieldSchema,
    opensearchMapping: threeFieldMapping,
  }),

  solrTest('query-bool-exclamation', {
    description: '! operator as NOT alternative (no whitespace needed)',
    documents: electronicsClothingDocs,
    requestPath: '/solr/testcollection/select?q=' + encodeURIComponent('category:electronics && !status:draft') + '&wt=json',
    solrSchema: threeFieldSchema,
    opensearchMapping: threeFieldMapping,
  }),

  // ───────────────────────────────────────────────────────────
  // Prefix operators (+/-) on field:value
  // ───────────────────────────────────────────────────────────

  solrTest('query-bool-plus-field', {
    description: '+ prefix for required field:value',
    documents: electronicsClothingDocs,
    requestPath: '/solr/testcollection/select?q=' + encodeURIComponent('+category:electronics') + '&wt=json',
    solrSchema: threeFieldSchema,
    opensearchMapping: threeFieldMapping,
  }),

  solrTest('query-bool-minus-field', {
    description: '- prefix for prohibited field:value',
    documents: electronicsClothingDocs,
    requestPath: '/solr/testcollection/select?q=' + encodeURIComponent('category:electronics -status:draft') + '&q.op=AND&wt=json',
    solrSchema: threeFieldSchema,
    opensearchMapping: threeFieldMapping,
  }),

  // ───────────────────────────────────────────────────────────
  // Prefix operators (+/-) on bare terms
  // ───────────────────────────────────────────────────────────

  solrTest('query-bool-plus-bare', {
    description: '+bareterm requires term, other terms optional (+jakarta lucene)',
    documents: [
      { id: '1', title: 'jakarta apache lucene', content: 'search library' },
      { id: '2', title: 'lucene basics', content: 'introduction' },
      { id: '3', title: 'solr guide', content: 'search server' },
    ],
    requestPath: '/solr/testcollection/select?q=' + encodeURIComponent('+jakarta lucene') + '&df=title&q.op=AND&wt=json',
    solrSchema: { fields: { title: { type: 'text_general' }, content: { type: 'text_general' } } },
    opensearchMapping: { properties: { title: { type: 'text' }, content: { type: 'text' } } },
  }),

  solrTest('query-bool-minus-bare', {
    description: '-bareterm prohibits term (lucene -jakarta)',
    documents: [
      { id: '1', title: 'jakarta apache lucene', content: 'search library' },
      { id: '2', title: 'lucene basics', content: 'introduction' },
      { id: '3', title: 'solr guide', content: 'search server' },
    ],
    requestPath: '/solr/testcollection/select?q=' + encodeURIComponent('lucene -jakarta') + '&df=title&q.op=AND&wt=json',
    solrSchema: { fields: { title: { type: 'text_general' }, content: { type: 'text_general' } } },
    opensearchMapping: { properties: { title: { type: 'text' }, content: { type: 'text' } } },
  }),

  // ───────────────────────────────────────────────────────────
  // Boolean with phrase queries
  // ───────────────────────────────────────────────────────────

  solrTest('query-bool-and-phrase', {
    description: 'AND with phrase query (field:"phrase")',
    documents: [
      { id: '1', title: 'hello world', category: 'greeting', status: 'active' },
      { id: '2', title: 'world hello', category: 'greeting', status: 'active' },
      { id: '3', title: 'hello there', category: 'greeting', status: 'draft' },
    ],
    requestPath: '/solr/testcollection/select?q=' + encodeURIComponent('title:"hello world" AND status:active') + '&wt=json',
    solrSchema: threeFieldSchema,
    opensearchMapping: threeFieldMapping,
  }),

  solrTest('query-bool-or-phrases', {
    description: 'OR between two phrase queries',
    documents: [
      { id: '1', title: 'hello world', category: 'greeting', status: 'active' },
      { id: '2', title: 'goodbye world', category: 'farewell', status: 'active' },
      { id: '3', title: 'hello there', category: 'greeting', status: 'active' },
    ],
    requestPath: '/solr/testcollection/select?q=' + encodeURIComponent('title:"hello world" OR title:"goodbye world"') + '&wt=json',
    solrSchema: threeFieldSchema,
    opensearchMapping: threeFieldMapping,
  }),

  solrTest('query-bool-not-phrase', {
    description: 'NOT with phrase query',
    documents: [
      { id: '1', title: 'hello world', category: 'greeting', status: 'active' },
      { id: '2', title: 'hello there', category: 'greeting', status: 'active' },
      { id: '3', title: 'goodbye world', category: 'farewell', status: 'active' },
    ],
    requestPath: '/solr/testcollection/select?q=' + encodeURIComponent('category:greeting AND NOT title:"hello world"') + '&wt=json',
    solrSchema: threeFieldSchema,
    opensearchMapping: threeFieldMapping,
  }),

  solrTest('query-bool-plus-bare-phrase', {
    description: '+ prefix on bare phrase (+"hello world")',
    documents: [
      { id: '1', title: 'hello world guide', content: 'introduction' },
      { id: '2', title: 'world hello', content: 'reversed' },
      { id: '3', title: 'hello there', content: 'greeting' },
    ],
    requestPath: '/solr/testcollection/select?q=' + encodeURIComponent('+"hello world"') + '&df=title&wt=json',
    solrSchema: { fields: { title: { type: 'text_general' }, content: { type: 'text_general' } } },
    opensearchMapping: { properties: { title: { type: 'text' }, content: { type: 'text' } } },
  }),

  solrTest('query-bool-minus-bare-phrase', {
    description: '- prefix on bare phrase (-"hello world")',
    documents: [
      { id: '1', title: 'hello world guide', content: 'introduction' },
      { id: '2', title: 'world hello', content: 'reversed' },
      { id: '3', title: 'hello there', content: 'greeting' },
    ],
    requestPath: '/solr/testcollection/select?q=' + encodeURIComponent('hello -"hello world"') + '&df=title&q.op=AND&wt=json',
    solrSchema: { fields: { title: { type: 'text_general' }, content: { type: 'text_general' } } },
    opensearchMapping: { properties: { title: { type: 'text' }, content: { type: 'text' } } },
  }),

  // ───────────────────────────────────────────────────────────
  // Precedence edge cases
  // ───────────────────────────────────────────────────────────

  solrTest('query-bool-precedence-or-and', {
    description: 'Precedence: X OR Y AND Z parses as X OR (Y AND Z)',
    documents: electronicsClothingDocs,
    // Should match: electronics (via OR), OR (clothing AND active)
    // Expected: id 1,2,3 (electronics) + id 4 (clothing AND active)
    requestPath: '/solr/testcollection/select?q=' + encodeURIComponent('category:electronics OR (category:clothing AND status:active)') + '&wt=json',
    solrSchema: threeFieldSchema,
    opensearchMapping: threeFieldMapping,
  }),

  solrTest('query-bool-precedence-grouped', {
    description: 'Grouped precedence: (X OR Y) AND Z',
    documents: electronicsClothingDocs,
    // Should match: (electronics OR clothing) AND active
    // Expected: id 1,3 (electronics AND active) + id 4 (clothing AND active)
    requestPath: '/solr/testcollection/select?q=' + encodeURIComponent('(category:electronics OR category:clothing) AND status:active') + '&wt=json',
    solrSchema: threeFieldSchema,
    opensearchMapping: threeFieldMapping,
  }),

  // ───────────────────────────────────────────────────────────
  // Multiple NOT clauses
  // ───────────────────────────────────────────────────────────

  solrTest('query-bool-multiple-not', {
    description: 'Multiple NOT clauses: A AND NOT B AND NOT C',
    documents: electronicsClothingDocs,
    // Should match: active AND NOT electronics AND NOT clothing
    // Expected: id 5 (tools, active)
    requestPath: '/solr/testcollection/select?q=' + encodeURIComponent('status:active AND NOT category:electronics AND NOT category:clothing') + '&wt=json',
    solrSchema: threeFieldSchema,
    opensearchMapping: threeFieldMapping,
  }),

  // ───────────────────────────────────────────────────────────
  // Nested boolean depth
  // ───────────────────────────────────────────────────────────

  solrTest('query-bool-nested-depth-3', {
    description: 'Nested boolean depth: (A OR (B AND (C OR D)))',
    documents: [
      { id: '1', title: 'laptop', category: 'electronics', status: 'active' },
      { id: '2', title: 'phone', category: 'electronics', status: 'draft' },
      { id: '3', title: 'shirt', category: 'clothing', status: 'active' },
      { id: '4', title: 'pants', category: 'clothing', status: 'draft' },
      { id: '5', title: 'hammer', category: 'tools', status: 'active' },
    ],
    // (category:tools OR (category:clothing AND (status:active OR status:pending)))
    // Should match: tools (id 5), OR clothing AND (active OR pending) (id 3)
    requestPath: '/solr/testcollection/select?q=' + encodeURIComponent('(category:tools OR (category:clothing AND (status:active OR status:pending)))') + '&wt=json',
    solrSchema: threeFieldSchema,
    opensearchMapping: threeFieldMapping,
  }),

  solrTest('query-bool-nested-mixed-operators', {
    description: 'Nested with mixed operators: ((A AND B) OR (C AND D))',
    documents: electronicsClothingDocs,
    // ((electronics AND active) OR (clothing AND active))
    // Expected: id 1,3 (electronics AND active) + id 4 (clothing AND active)
    requestPath: '/solr/testcollection/select?q=' + encodeURIComponent('((category:electronics AND status:active) OR (category:clothing AND status:active))') + '&wt=json',
    solrSchema: threeFieldSchema,
    opensearchMapping: threeFieldMapping,
  }),
];
