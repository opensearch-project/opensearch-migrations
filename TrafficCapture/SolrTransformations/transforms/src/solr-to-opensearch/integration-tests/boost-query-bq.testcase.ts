/**
 * Integration test cases for bq (boost query) parameter — DisMax and eDisMax.
 *
 * bq adds optional query clauses that additively influence scoring.
 * Both parsers support the same bq syntax. Test cases cover both defType values.
 *
 * DisMax does not support *:* or field:value syntax in q, so all tests use
 * bare terms with qf to ensure compatibility with both parsers.
 *
 * Boolean operators (AND) and negation (-) in bq values produce different
 * results in DisMax vs eDisMax due to how DisMax counts bq should clauses
 * against mm (minimum should match). Those tests are eDisMax-only.
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

function bqPath(q: string, defType: string, bq: string | string[], extra = ''): string {
  const bqArr = Array.isArray(bq) ? bq : [bq];
  const bqParams = bqArr.map((v) => '&bq=' + encodeURIComponent(v)).join('');
  return '/solr/testcollection/select?q=' + encodeURIComponent(q)
    + '&defType=' + defType
    + '&qf=' + encodeURIComponent('title')
    + bqParams
    + extra
    + '&wt=json';
}

/** Tests that work identically on both DisMax and eDisMax. */
function makeSharedBqCases(defType: 'edismax' | 'dismax'): TestCase[] {
  return [
    solrTest(`${defType}-bq-single`, {
      description: 'Single bq boosts documents matching the boost clause',
      documents: docs,
      requestPath: bqPath('cheese', defType, 'category:food^10'),
      solrSchema: schema,
      opensearchMapping: mapping,
      assertionRules: [...SOLR_INTERNAL_RULES, scoreRule],
    }),

    solrTest(`${defType}-bq-multiple`, {
      description: 'Multiple bq params each add separate boost clauses',
      documents: docs,
      requestPath: bqPath('cheese', defType, ['category:food^10', 'category:kitchen^5']),
      solrSchema: schema,
      opensearchMapping: mapping,
      assertionRules: [...SOLR_INTERNAL_RULES, scoreRule],
    }),

    solrTest(`${defType}-bq-phrase`, {
      description: 'bq with phrase boost query',
      documents: docs,
      requestPath: bqPath('cheese', defType, 'title:"aged cheese"^5'),
      solrSchema: schema,
      opensearchMapping: mapping,
      assertionRules: [...SOLR_INTERNAL_RULES, scoreRule],
    }),

    solrTest(`${defType}-bq-range`, {
      description: 'bq with range boost query',
      documents: docs,
      requestPath: bqPath('cheese', defType, 'price:[0 TO 20]^10'),
      solrSchema: schema,
      opensearchMapping: mapping,
      assertionRules: [...SOLR_INTERNAL_RULES, scoreRule],
    }),

    solrTest(`${defType}-bq-with-fq`, {
      description: 'bq and fq together — fq filters, bq boosts',
      documents: docs,
      requestPath: bqPath('cheese', defType, 'category:food^10', '&fq=' + encodeURIComponent('price:[10 TO 30]')),
      solrSchema: schema,
      opensearchMapping: mapping,
      assertionRules: [...SOLR_INTERNAL_RULES, scoreRule],
    }),

    solrTest(`${defType}-bq-grouped`, {
      description: 'bq with grouped OR expression and boost',
      documents: docs,
      requestPath: bqPath('cheese', defType, '(category:food OR category:kitchen)^5'),
      solrSchema: schema,
      opensearchMapping: mapping,
      assertionRules: [...SOLR_INTERNAL_RULES, scoreRule],
    }),

    solrTest(`${defType}-bq-bare-term`, {
      description: 'bq with bare term (no field prefix)',
      documents: docs,
      requestPath: bqPath('cheese', defType, 'aged^5'),
      solrSchema: schema,
      opensearchMapping: mapping,
      assertionRules: [...SOLR_INTERNAL_RULES, scoreRule],
    }),
  ];
}

export const testCases: TestCase[] = [
  ...makeSharedBqCases('edismax'),
  ...makeSharedBqCases('dismax'),

  // eDisMax-only: boolean operators and negation in bq values.
  // DisMax counts bq should clauses against mm (minimum should match),
  // causing boolean/negation bq clauses to affect matching — not just scoring.
  // eDisMax excludes bq clauses from mm counting, so these work correctly.
  // See LIMITATIONS.md BQ-DISMAX-MM for details.
  solrTest('edismax-bq-boolean', {
    description: 'bq with boolean expression (AND) — eDisMax only',
    documents: docs,
    requestPath: bqPath('cheese', 'edismax', 'category:food AND price:[0 TO 30]'),
    solrSchema: schema,
    opensearchMapping: mapping,
    assertionRules: [...SOLR_INTERNAL_RULES, scoreRule],
  }),

  solrTest('edismax-bq-negation', {
    description: 'bq with negation — demote matching docs — eDisMax only',
    documents: docs,
    requestPath: bqPath('cheese', 'edismax', '-category:food^5'),
    solrSchema: schema,
    opensearchMapping: mapping,
    assertionRules: [...SOLR_INTERNAL_RULES, scoreRule],
  }),

  solrTest('edismax-bq-duplicate', {
    description: 'Duplicate bq params each contribute separate scoring — not deduplicated',
    documents: docs,
    requestPath: bqPath('cheese', 'edismax', ['category:food^10', 'category:food^10']),
    solrSchema: schema,
    opensearchMapping: mapping,
    assertionRules: [...SOLR_INTERNAL_RULES, scoreRule],
  }),
];
