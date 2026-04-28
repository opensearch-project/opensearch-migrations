/**
 * E2E test cases for solrconfig.xml defaults integration.
 * Tests that handler defaults (df) are applied during query translation.
 *
 * All test cases in this file share the same transform bindings (one fixture per file).
 * Shared solrConfig: df=title, rows=10
 */
import { solrTest, SOLR_INTERNAL_RULES } from '../test-types';

/** Shared bindings for all tests in this file. */
const SOLR_CONFIG_BINDINGS = {
  solrConfig: {
    '/select': {
      defaults: { df: 'title', rows: '10', wt: 'json' },
    },
  },
};

/**
 * Bindings in the same JSON format as --transformerConfig with inline
 * bindingsObject. Verifies the JSON-based config path works end-to-end.
 */
const SOLR_CONFIG_JSON_BINDINGS = {
  solrConfig: {
    '/select': {
      defaults: { df: 'content', rows: '2' },
    },
  },
};

export const testCases = [
  solrTest('solrconfig-default-df-applied', {
    description: 'Bare query without df uses df=title from solrconfig defaults — matches Solr behavior',
    documents: [
      { id: '1', title: 'Java Programming', content: 'Learn Java basics' },
      { id: '2', title: 'Python Cookbook', content: 'Recipes for Python devs' },
      { id: '3', title: 'Go in Action', content: 'Concurrency in Go' },
    ],
    requestPath: '/solr/testcollection/select?q=Java&fl=id,title&wt=json',
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
    assertionRules: SOLR_INTERNAL_RULES,
    transformBindings: SOLR_CONFIG_BINDINGS,
  }),

  solrTest('solrconfig-explicit-df-overrides-default', {
    description: 'Explicit df=content overrides solrconfig default df=title',
    documents: [
      { id: '1', title: 'Java Programming', content: 'Learn Java basics' },
      { id: '2', title: 'Python Cookbook', content: 'Java recipes for Python devs' },
    ],
    requestPath: '/solr/testcollection/select?q=Java&df=content&fl=id,title&wt=json',
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
    assertionRules: SOLR_INTERNAL_RULES,
    transformBindings: SOLR_CONFIG_BINDINGS,
  }),

  solrTest('solrconfig-default-rows-not-overridden-by-explicit', {
    description: 'Explicit rows=1 overrides solrconfig default rows=10',
    documents: [
      { id: '1', title: 'Doc One' },
      { id: '2', title: 'Doc Two' },
      { id: '3', title: 'Doc Three' },
    ],
    requestPath: '/solr/testcollection/select?q=*:*&rows=1&fl=id,title&wt=json',
    solrSchema: {
      fields: { title: { type: 'text_general' } },
    },
    opensearchMapping: {
      properties: { title: { type: 'text' } },
    },
    assertionRules: SOLR_INTERNAL_RULES,
    transformBindings: SOLR_CONFIG_BINDINGS,
  }),

  solrTest('solrconfig-df-does-not-affect-field-specific-query', {
    description: 'Field-specific query title:Java is unaffected by df default',
    documents: [
      { id: '1', title: 'Java Programming', content: 'Learn basics' },
      { id: '2', title: 'Python Cookbook', content: 'Java recipes' },
    ],
    requestPath: '/solr/testcollection/select?q=title:Java&fl=id,title&wt=json',
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
    assertionRules: SOLR_INTERNAL_RULES,
    transformBindings: SOLR_CONFIG_BINDINGS,
  }),

  solrTest('solrconfig-defaults-with-highlighting', {
    description: 'Default df=title works correctly combined with highlighting',
    documents: [
      { id: '1', title: 'Java Basics', content: 'Introduction to Java' },
      { id: '2', title: 'Java Advanced', content: 'Deep dive into Java' },
      { id: '3', title: 'Go in Action', content: 'Concurrency in Go' },
    ],
    requestPath: '/solr/testcollection/select?q=Java&hl=true&hl.fl=title&fl=id,title&wt=json',
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
    assertionRules: [
      ...SOLR_INTERNAL_RULES,
      { path: '$.highlighting[*][*][*]', rule: 'regex', expected: '.*<em>.*</em>.*', reason: 'Highlight fragment text may differ' },
    ],
    transformBindings: SOLR_CONFIG_BINDINGS,
  }),

  solrTest('solrconfig-json-bindings-df-applied', {
    description: 'JSON-format solrConfig bindings (as from --transformerConfig with inline bindingsObject) — df=content applied',
    documents: [
      { id: '1', title: 'Java Programming', content: 'Learn Java basics' },
      { id: '2', title: 'Python Cookbook', content: 'Java recipes for Python devs' },
    ],
    requestPath: '/solr/testcollection/select?q=Java&fl=id,title,content&wt=json',
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
    assertionRules: SOLR_INTERNAL_RULES,
    transformBindings: SOLR_CONFIG_JSON_BINDINGS,
  }),

  solrTest('solrconfig-rows-default-applied-when-absent', {
    description: 'Default rows=2 from solrConfig is applied when rows is not in the URL — verifies URLSearchParams.set() works in GraalVM polyfill',
    documents: [
      { id: '1', title: 'Doc A', content: 'first' },
      { id: '2', title: 'Doc B', content: 'second' },
      { id: '3', title: 'Doc C', content: 'third' },
      { id: '4', title: 'Doc D', content: 'fourth' },
    ],
    requestPath: '/solr/testcollection/select?q=*:*&fl=id,title&wt=json',
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
    assertionRules: SOLR_INTERNAL_RULES,
    transformBindings: SOLR_CONFIG_JSON_BINDINGS,
  }),

  solrTest('solrconfig-invariant-df-overrides-request', {
    description: 'Invariant df=title overrides explicit df=content in request — searches title field regardless',
    documents: [
      { id: '1', title: 'Java Programming', content: 'Learn basics' },
      { id: '2', title: 'Python Cookbook', content: 'Java recipes for devs' },
    ],
    requestPath: '/solr/testcollection/select?q=Java&df=content&fl=id,title&wt=json',
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
    assertionRules: SOLR_INTERNAL_RULES,
    transformBindings: {
      solrConfig: {
        '/select': {
          invariants: { df: 'title' },
        },
      },
    },
  }),
];
