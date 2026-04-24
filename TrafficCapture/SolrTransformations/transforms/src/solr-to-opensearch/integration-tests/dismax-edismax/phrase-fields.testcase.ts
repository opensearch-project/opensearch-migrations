/**
 * Integration test cases for pf (phrase fields), ps (phrase slop), and tie (tie breaker).
 *
 * pf: after matching via qf, documents where all terms appear as a phrase in pf
 *     fields get an additive score boost. Scoring-only — never affects which docs match.
 * ps: phrase slop — how far apart terms can be and still qualify for the pf phrase boost.
 * tie: tie breaker — how much non-best-matching qf fields contribute to the score.
 *
 * All cases run for both edismax and dismax since they share the same pf/ps/tie behavior.
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

function p(q: string, defType: string, extra: Record<string, string> = {}): string {
  const base = '/solr/testcollection/select?q=' + encodeURIComponent(q) + '&defType=' + defType;
  const rest = Object.entries(extra)
    .map(([k, v]) => `&${k}=${encodeURIComponent(v)}`)
    .join('');
  return base + rest + '&wt=json';
}

function makeSharedCases(defType: 'edismax' | 'dismax'): TestCase[] {
  return [
    // ─── pf — phrase boost ──────────────────────────────────────────────────

    solrTest(`${defType}-pf-no-boost`, {
      description: 'pf without boost value — default boost applies',
      documents: [
        { id: '1', title: 'java programming tutorial', body: 'unrelated content' },
        { id: '2', title: 'programming java tutorial', body: 'unrelated content' },
        { id: '3', title: 'unrelated content', body: 'java programming guide' },
      ],
      requestPath: p('java programming', defType, { qf: 'title body', pf: 'title' }),
      solrSchema: twoFieldSchema,
      opensearchMapping: twoFieldMapping,
    }),

    solrTest(`${defType}-pf-boosts-exact-phrase`, {
      description: 'pf boosts doc where all terms appear as adjacent phrase',
      documents: [
        { id: '1', title: 'java programming tutorial', body: 'unrelated content' },
        { id: '2', title: 'programming java tutorial', body: 'unrelated content' },
        { id: '3', title: 'unrelated content', body: 'java programming guide' },
      ],
      requestPath: p('java programming', defType, { qf: 'title body', pf: 'title^50' }),
      solrSchema: twoFieldSchema,
      opensearchMapping: twoFieldMapping,
    }),

    solrTest(`${defType}-pf-multi-fields-with-qf`, {
      description: 'pf across multiple fields — both title and body can contribute phrase boost',
      documents: [
        { id: '1', title: 'java programming', body: 'unrelated content' },
        { id: '2', title: 'unrelated content', body: 'java programming guide' },
        { id: '3', title: 'java', body: 'programming tutorial' },
      ],
      requestPath: p('java programming', defType, { qf: 'title body', pf: 'title^50 body^20' }),
      solrSchema: twoFieldSchema,
      opensearchMapping: twoFieldMapping,
    }),

    solrTest(`${defType}-pf-different-boost-than-qf`, {
      description: 'pf field boosts differ from qf field boosts independently',
      documents: [
        { id: '1', title: 'java programming', body: 'unrelated content' },
        { id: '2', title: 'unrelated content', body: 'java programming guide' },
        { id: '3', title: 'java tutorial', body: 'programming basics' },
      ],
      requestPath: p('java programming', defType, { qf: 'title^1 body^1', pf: 'title^100 body^5' }),
      solrSchema: twoFieldSchema,
      opensearchMapping: twoFieldMapping,
    }),

    solrTest(`${defType}-pf-high-qf-boost-with-pf`, {
      description: 'qf with boost > 1 combined with pf — title matches score higher on both axes',
      documents: [
        { id: '1', title: 'java programming', body: 'unrelated content' },
        { id: '2', title: 'unrelated content', body: 'java programming guide' },
        { id: '3', title: 'java tutorial', body: 'programming reference' },
      ],
      requestPath: p('java programming', defType, { qf: 'title^5 body', pf: 'title^50 body^10' }),
      solrSchema: twoFieldSchema,
      opensearchMapping: twoFieldMapping,
    }),

    // ─── ps — phrase slop ───────────────────────────────────────────────────

    solrTest(`${defType}-pf-ps-slop-allows-gap`, {
      description: 'ps=2 allows up to 2 words between terms and still phrase-boosts',
      documents: [
        { id: '1', title: 'java programming', body: 'unrelated content' },
        { id: '2', title: 'java advanced programming', body: 'unrelated content' },
        { id: '3', title: 'programming java tutorial', body: 'unrelated content' },
      ],
      requestPath: p('java programming', defType, { qf: 'title body', pf: 'title^50', ps: '2' }),
      solrSchema: twoFieldSchema,
      opensearchMapping: twoFieldMapping,
    }),

    solrTest(`${defType}-pf-ps0-strict-adjacency`, {
      description: 'ps=0 (default) only boosts exact adjacent phrase — gap prevents boost',
      documents: [
        { id: '1', title: 'java programming', body: 'unrelated content' },
        { id: '2', title: 'java advanced programming', body: 'unrelated content' },
        { id: '3', title: 'unrelated content', body: 'java programming guide' },
      ],
      requestPath: p('java programming', defType, { qf: 'title body', pf: 'title^50', ps: '0' }),
      solrSchema: twoFieldSchema,
      opensearchMapping: twoFieldMapping,
    }),

    solrTest(`${defType}-pf-ps-with-multi-pf-fields`, {
      description: 'ps applies to all pf fields — slop on both title and body phrase queries',
      documents: [
        { id: '1', title: 'java great programming', body: 'unrelated content' },
        { id: '2', title: 'unrelated content', body: 'java best programming guide' },
        { id: '3', title: 'java programming', body: 'java programming' },
      ],
      requestPath: p('java programming', defType, { qf: 'title body', pf: 'title^50 body^20', ps: '3' }),
      solrSchema: twoFieldSchema,
      opensearchMapping: twoFieldMapping,
    }),

    // ─── tie — tie breaker ──────────────────────────────────────────────────

    solrTest(`${defType}-tie-zero-pure-dismax`, {
      description: 'tie=0 (default) — only best field score counts, no bonus for multi-field match',
      documents: [
        { id: '1', title: 'java programming', body: 'java tutorial' },
        { id: '2', title: 'java programming', body: 'unrelated content' },
        { id: '3', title: 'unrelated content', body: 'java tutorial guide' },
      ],
      requestPath: p('java', defType, { qf: 'title body', tie: '0.0' }),
      solrSchema: twoFieldSchema,
      opensearchMapping: twoFieldMapping,
    }),

    solrTest(`${defType}-tie-moderate`, {
      description: 'tie=0.3 gives moderate bonus to docs matching in multiple qf fields',
      documents: [
        { id: '1', title: 'java programming', body: 'java tutorial' },
        { id: '2', title: 'java programming', body: 'unrelated content' },
        { id: '3', title: 'unrelated content', body: 'java tutorial guide' },
      ],
      requestPath: p('java', defType, { qf: 'title body', tie: '0.3' }),
      solrSchema: twoFieldSchema,
      opensearchMapping: twoFieldMapping,
    }),

    solrTest(`${defType}-tie-one-pure-sum`, {
      description: 'tie=1.0 — all field scores summed, equivalent to most_fields behavior',
      documents: [
        { id: '1', title: 'java programming', body: 'java tutorial' },
        { id: '2', title: 'java programming', body: 'unrelated content' },
        { id: '3', title: 'unrelated content', body: 'java tutorial guide' },
      ],
      requestPath: p('java', defType, { qf: 'title body', tie: '1.0' }),
      solrSchema: twoFieldSchema,
      opensearchMapping: twoFieldMapping,
    }),

    // ─── combined pf + ps + tie ─────────────────────────────────────────────

    solrTest(`${defType}-pf-ps-tie-combined`, {
      description: 'pf, ps, and tie all set together — full dismax/edismax scoring',
      documents: [
        { id: '1', title: 'java programming tutorial', body: 'java tutorial' },
        { id: '2', title: 'java advanced programming', body: 'java tutorial' },
        { id: '3', title: 'java programming', body: 'unrelated content' },
        { id: '4', title: 'unrelated content', body: 'java programming guide' },
        { id: '5', title: 'java tutorial', body: 'programming reference' },
        { id: '6', title: 'programming basics', body: 'java fundamentals' },
      ],
      requestPath: p('java programming', defType, {
        qf: 'title^2 body',
        pf: 'title^50 body^20',
        ps: '2',
        tie: '0.3',
      }),
      solrSchema: twoFieldSchema,
      opensearchMapping: twoFieldMapping,
    }),
  ];
}

export const testCases: TestCase[] = [
  ...makeSharedCases('edismax'),
  ...makeSharedCases('dismax'),
];
