/**
 * Integration test cases for qf (query fields) parameter — eDisMax and DisMax.
 *
 * Both parsers support the same qf syntax and multi-field expansion behavior
 * for bare terms. Test cases are generated for both defType values to avoid
 * duplication. eDisMax-specific cases (field:value syntax) are appended at the end.
 */
import { solrTest } from '../../../test-types';
import type { TestCase } from '../../../test-types';

const twoFieldSchema = {
  fields: {
    title: { type: 'text_general' as const },
    body: { type: 'text_general' as const },
  },
};

const twoFieldMapping = {
  properties: {
    title: { type: 'text' as const },
    body: { type: 'text' as const },
  },
};

function qfPath(q: string, defType: string, qf: string): string {
  return '/solr/testcollection/select?q=' + encodeURIComponent(q)
    + '&defType=' + defType
    + '&qf=' + encodeURIComponent(qf)
    + '&wt=json';
}

function makeQfCases(defType: 'edismax' | 'dismax'): TestCase[] {
  return [
    solrTest(`${defType}-bare-term-single-qf`, {
      description: 'Bare term with single qf field matches that field',
      documents: [
        { id: '1', title: 'java programming', body: 'unrelated' },
        { id: '2', title: 'python programming', body: 'java tutorial' },
        { id: '3', title: 'unrelated', body: 'unrelated' },
      ],
      requestPath: qfPath('java', defType, 'title'),
      solrSchema: twoFieldSchema,
      opensearchMapping: twoFieldMapping,
    }),

    solrTest(`${defType}-bare-term-multi-qf`, {
      description: 'Bare term with multiple qf fields matches across all of them',
      documents: [
        { id: '1', title: 'java programming', body: 'unrelated' },
        { id: '2', title: 'unrelated', body: 'java tutorial' },
        { id: '3', title: 'python', body: 'python' },
      ],
      requestPath: qfPath('java', defType, 'title body'),
      solrSchema: twoFieldSchema,
      opensearchMapping: twoFieldMapping,
    }),

    solrTest(`${defType}-bare-term-qf-with-boost`, {
      description: 'Bare term with one boosted qf field — title match scores higher than body',
      documents: [
        { id: '1', title: 'java programming', body: 'unrelated' },
        { id: '2', title: 'unrelated', body: 'java tutorial' },
      ],
      requestPath: qfPath('java', defType, 'title^2 body'),
      solrSchema: twoFieldSchema,
      opensearchMapping: twoFieldMapping,
    }),

    solrTest(`${defType}-bare-term-qf-all-boosted`, {
      description: 'Bare term with all qf fields having boost values',
      documents: [
        { id: '1', title: 'java programming', body: 'unrelated' },
        { id: '2', title: 'unrelated', body: 'java tutorial' },
      ],
      requestPath: qfPath('java', defType, 'title^2 body^1'),
      solrSchema: twoFieldSchema,
      opensearchMapping: twoFieldMapping,
    }),

    solrTest(`${defType}-bare-phrase-qf`, {
      description: 'Bare phrase with qf fields uses phrase matching',
      documents: [
        { id: '1', title: 'hello world greeting', body: 'unrelated' },
        { id: '2', title: 'world hello reversed', body: 'unrelated' },
        { id: '3', title: 'unrelated', body: 'hello world message' },
      ],
      requestPath: qfPath('"hello world"', defType, 'title body'),
      solrSchema: twoFieldSchema,
      opensearchMapping: twoFieldMapping,
    }),
  ];
}

export const testCases: TestCase[] = [
  ...makeQfCases('edismax'),
  ...makeQfCases('dismax'),

  // edismax only — explicit field:value syntax is supported (in dismax it's treated as a literal string)
  solrTest('edismax-field-query-still-works', {
    description: 'Explicit field:value syntax still works in edismax (unlike dismax)',
    documents: [
      { id: '1', title: 'java', body: 'unrelated' },
      { id: '2', title: 'unrelated', body: 'java' },
    ],
    requestPath: '/solr/testcollection/select?q=' + encodeURIComponent('title:java') + '&defType=edismax&qf=' + encodeURIComponent('title body') + '&wt=json',
    solrSchema: twoFieldSchema,
    opensearchMapping: twoFieldMapping,
  }),
];
