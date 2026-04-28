/**
 * Integration test cases for mm (minimum should match) parameter — DisMax and eDisMax.
 *
 * The mm parameter controls how many optional (should) clauses must match.
 * Both defType=dismax and defType=edismax support the same mm syntax.
 * Tests are generated for both to avoid duplication.
 */
import { solrTest } from '../../test-types';
import type { TestCase } from '../../test-types';

const schema = {
  fields: {
    title: { type: 'text_general' as const },
    body: { type: 'text_general' as const },
  },
};

const mapping = {
  properties: {
    title: { type: 'text' as const },
    body: { type: 'text' as const },
  },
};

const docs = [
  { id: '1', title: 'java python ruby', body: 'languages' },
  { id: '2', title: 'java python', body: 'two languages' },
  { id: '3', title: 'java', body: 'one language' },
  { id: '4', title: 'unrelated', body: 'nothing here' },
];

function mmPath(q: string, defType: string, mm: string, qf = 'title'): string {
  return '/solr/testcollection/select?q=' + encodeURIComponent(q)
    + '&defType=' + defType
    + '&qf=' + encodeURIComponent(qf)
    + '&mm=' + encodeURIComponent(mm)
    + '&wt=json';
}

function makeMmCases(defType: 'dismax' | 'edismax'): TestCase[] {
  return [
    solrTest(`${defType}-mm-positive-integer`, {
      description: 'mm=2 requires at least 2 of 3 terms to match',
      documents: docs,
      requestPath: mmPath('java python ruby', defType, '2'),
      solrSchema: schema,
      opensearchMapping: mapping,
    }),

    solrTest(`${defType}-mm-100-percent`, {
      description: 'mm=100% requires all terms to match',
      documents: docs,
      requestPath: mmPath('java python ruby', defType, '100%'),
      solrSchema: schema,
      opensearchMapping: mapping,
    }),

    solrTest(`${defType}-mm-negative-integer`, {
      description: 'mm=-1 allows one term to be missing',
      documents: docs,
      requestPath: mmPath('java python ruby', defType, '-1'),
      solrSchema: schema,
      opensearchMapping: mapping,
    }),

    solrTest(`${defType}-mm-percentage`, {
      description: 'mm=75% with 3 terms requires floor(3*0.75)=2 to match',
      documents: docs,
      requestPath: mmPath('java python ruby', defType, '75%'),
      solrSchema: schema,
      opensearchMapping: mapping,
    }),

    solrTest(`${defType}-mm-single-term`, {
      description: 'mm with single term — always requires that term',
      documents: docs,
      requestPath: mmPath('java', defType, '100%'),
      solrSchema: schema,
      opensearchMapping: mapping,
    }),

    solrTest(`${defType}-mm-conditional`, {
      description: 'mm=3<90% — if <=3 clauses all required, else 90%',
      documents: docs,
      requestPath: mmPath('java python ruby', defType, '3<90%'),
      solrSchema: schema,
      opensearchMapping: mapping,
    }),

    solrTest(`${defType}-mm-multiple-conditionals`, {
      description: 'mm=2<-25% 9<-3 — tiered conditional expression',
      documents: docs,
      requestPath: mmPath('java python ruby', defType, '2<-25% 9<-3'),
      solrSchema: schema,
      opensearchMapping: mapping,
    }),
  ];
}

export const testCases: TestCase[] = [
  ...makeMmCases('dismax'),
  ...makeMmCases('edismax'),

  // --- Error paths ---

  solrTest('mm-autoRelax-rejected', {
    description: 'mm.autoRelax is unsupported and should be rejected by validation',
    documents: [{ id: '1', title: 'test' }],
    requestPath: '/solr/testcollection/select?q=java%20python&defType=edismax&qf=title&mm=2&mm.autoRelax=true&wt=json',
    expectedStatusCode: 500,
    expectedErrorContains: 'Request transform failed',
  }),

  solrTest('mm-invalid-value-rejected', {
    description: 'Invalid mm value with alphabetic characters should be rejected',
    documents: [{ id: '1', title: 'test' }],
    requestPath: '/solr/testcollection/select?q=java%20python&defType=dismax&qf=title&mm=abc&wt=json',
    expectedStatusCode: 500,
    expectedErrorContains: 'Request transform failed',
  }),
];
