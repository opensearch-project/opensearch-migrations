/**
 * Integration test cases for bq (boost query) parameter — eDisMax only.
 *
 * bq adds optional query clauses that additively influence scoring.
 * defType=dismax is not supported by the shim — use edismax instead.
 *
 * Adding a new test: just add a solrTest() entry below.
 * It automatically runs against every Solr version in matrix.config.ts.
 */
import { solrTest, SOLR_INTERNAL_RULES } from '../../test-types';
import type { TestCase } from '../../test-types';

const schema = {
  fields: {
    title: { type: 'text_general' as const },
    category: { type: 'text_general' as const },
    price: { type: 'pint' as const },
  },
};

const mapping = {
  properties: {
    title: { type: 'text' as const },
    category: { type: 'text' as const },
    price: { type: 'integer' as const },
  },
};

const docs = [
  { id: '1', title: 'aged cheese wheel', category: 'food', price: 25 },
  { id: '2', title: 'cheese slicer tool', category: 'kitchen', price: 15 },
  { id: '3', title: 'cheese platter deluxe', category: 'food', price: 40 },
];

const scoreRule = { path: '$.response.docs[*].score', rule: 'loose-type' as const, reason: 'Score precision may differ' };

function bqPath(q: string, bq: string | string[], extra = ''): string {
  const bqArr = Array.isArray(bq) ? bq : [bq];
  const bqParams = bqArr.map((v) => '&bq=' + encodeURIComponent(v)).join('');
  return '/solr/testcollection/select?q=' + encodeURIComponent(q)
    + '&defType=edismax'
    + '&qf=' + encodeURIComponent('title')
    + bqParams
    + extra
    + '&wt=json';
}

export const testCases: TestCase[] = [
  solrTest('edismax-bq-single', {
    description: 'Single bq boosts documents matching the boost clause',
    documents: docs,
    requestPath: bqPath('cheese', 'category:food^10'),
    solrSchema: schema,
    opensearchMapping: mapping,
    assertionRules: [...SOLR_INTERNAL_RULES, scoreRule],
  }),

  solrTest('edismax-bq-multiple', {
    description: 'Multiple bq params each add separate boost clauses',
    documents: docs,
    requestPath: bqPath('cheese', ['category:food^10', 'category:kitchen^5']),
    solrSchema: schema,
    opensearchMapping: mapping,
    assertionRules: [...SOLR_INTERNAL_RULES, scoreRule],
  }),

  solrTest('edismax-bq-phrase', {
    description: 'bq with phrase boost query',
    documents: docs,
    requestPath: bqPath('cheese', 'title:"aged cheese"^5'),
    solrSchema: schema,
    opensearchMapping: mapping,
    assertionRules: [...SOLR_INTERNAL_RULES, scoreRule],
  }),

  solrTest('edismax-bq-range', {
    description: 'bq with range boost query',
    documents: docs,
    requestPath: bqPath('cheese', 'price:[0 TO 20]^10'),
    solrSchema: schema,
    opensearchMapping: mapping,
    assertionRules: [...SOLR_INTERNAL_RULES, scoreRule],
  }),

  solrTest('edismax-bq-with-fq', {
    description: 'bq and fq together — fq filters, bq boosts',
    documents: docs,
    requestPath: bqPath('cheese', 'category:food^10', '&fq=' + encodeURIComponent('price:[10 TO 30]')),
    solrSchema: schema,
    opensearchMapping: mapping,
    assertionRules: [...SOLR_INTERNAL_RULES, scoreRule],
  }),

  solrTest('edismax-bq-grouped', {
    description: 'bq with grouped OR expression and boost',
    documents: docs,
    requestPath: bqPath('cheese', '(category:food OR category:kitchen)^5'),
    solrSchema: schema,
    opensearchMapping: mapping,
    assertionRules: [...SOLR_INTERNAL_RULES, scoreRule],
  }),

  solrTest('edismax-bq-bare-term', {
    description: 'bq with bare term (no field prefix)',
    documents: docs,
    requestPath: bqPath('cheese', 'aged^5'),
    solrSchema: schema,
    opensearchMapping: mapping,
    assertionRules: [...SOLR_INTERNAL_RULES, scoreRule],
  }),

  solrTest('edismax-bq-boolean', {
    description: 'bq with boolean expression (AND)',
    documents: docs,
    requestPath: bqPath('cheese', 'category:food AND price:[0 TO 30]'),
    solrSchema: schema,
    opensearchMapping: mapping,
    assertionRules: [...SOLR_INTERNAL_RULES, scoreRule],
  }),

  solrTest('edismax-bq-negation', {
    description: 'bq with negation — demote matching docs',
    documents: docs,
    requestPath: bqPath('cheese', '-category:food^5'),
    solrSchema: schema,
    opensearchMapping: mapping,
    assertionRules: [...SOLR_INTERNAL_RULES, scoreRule],
  }),

  solrTest('edismax-bq-duplicate', {
    description: 'Duplicate bq params each contribute separate scoring — not deduplicated',
    documents: docs,
    requestPath: bqPath('cheese', ['category:food^10', 'category:food^10']),
    solrSchema: schema,
    opensearchMapping: mapping,
    assertionRules: [...SOLR_INTERNAL_RULES, scoreRule],
  }),

  // --- Error paths ---

  solrTest('dismax-bq-rejected', {
    description: 'bq with defType=dismax is rejected — use edismax instead',
    documents: [{ id: '1', title: 'cheese', category: 'food', price: 10 }],
    requestPath: '/solr/testcollection/select?q=cheese&defType=dismax&qf=title&bq=' + encodeURIComponent('category:food^10') + '&wt=json',
    expectedStatusCode: 500,
    expectedErrorContains: 'Request transform failed',
  }),
];
