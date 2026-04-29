/**
 * E2E test cases for batch/bulk operations, mixed commands, and commit.
 *
 * Tests run against real Solr 8/9 + OpenSearch containers via the shim.
 * Each test seeds documents, sends the batch/commit request, and compares
 * the Solr-direct response with the shim-translated response.
 */
import { solrTest } from '../test-types';
import type { TestCase } from '../test-types';

const UPDATE_RESPONSE_RULES = [
  { path: '$.responseHeader.QTime', rule: 'ignore' as const, reason: 'Timing varies' },
  { path: '$.responseHeader.params', rule: 'ignore' as const, reason: 'Solr echoes params, proxy does not' },
];

export const testCases: TestCase[] = [
  // --- Batch add ---

  solrTest('batch-add-json-docs-array', {
    description: 'Batch add via /update/json/docs with array body',
    documents: [],
    method: 'POST',
    requestBody: JSON.stringify([
      { id: 'batch-jd-1', title: 'first' },
      { id: 'batch-jd-2', title: 'second' },
      { id: 'batch-jd-3', title: 'third' },
    ]),
    requestPath: '/solr/testcollection/update/json/docs?commit=true',
    solrSchema: { fields: { title: { type: 'text_general' } } },
    opensearchMapping: { properties: { title: { type: 'text' } } },
    assertionRules: UPDATE_RESPONSE_RULES,
  }),

  solrTest('batch-add-command-format', {
    description: 'Batch add via {"add":[...]} SolrJ command format',
    documents: [],
    method: 'POST',
    requestBody: JSON.stringify({
      add: [
        { doc: { id: 'batch-cmd-1', title: 'alpha' } },
        { doc: { id: 'batch-cmd-2', title: 'beta' } },
      ],
    }),
    requestPath: '/solr/testcollection/update?commit=true',
    solrSchema: { fields: { title: { type: 'text_general' } } },
    opensearchMapping: { properties: { title: { type: 'text' } } },
    assertionRules: UPDATE_RESPONSE_RULES,
  }),

  solrTest('batch-add-with-commit-param', {
    description: 'Batch add with commit=true for immediate visibility',
    documents: [],
    method: 'POST',
    requestBody: JSON.stringify([
      { id: 'batch-vis-1', title: 'visible one' },
      { id: 'batch-vis-2', title: 'visible two' },
    ]),
    requestPath: '/solr/testcollection/update/json/docs?commit=true',
    solrSchema: { fields: { title: { type: 'text_general' } } },
    opensearchMapping: { properties: { title: { type: 'text' } } },
    assertionRules: UPDATE_RESPONSE_RULES,
  }),

  solrTest('batch-add-single-element-array', {
    description: 'Batch add with single-element array (edge case)',
    documents: [],
    method: 'POST',
    requestBody: JSON.stringify([{ id: 'batch-single-1', title: 'only one' }]),
    requestPath: '/solr/testcollection/update/json/docs?commit=true',
    solrSchema: { fields: { title: { type: 'text_general' } } },
    opensearchMapping: { properties: { title: { type: 'text' } } },
    assertionRules: UPDATE_RESPONSE_RULES,
  }),

  solrTest('batch-add-multiple-fields', {
    description: 'Batch add with documents containing multiple fields',
    documents: [],
    method: 'POST',
    requestBody: JSON.stringify([
      { id: 'batch-mf-1', title: 'product A', star_rating: 5 },
      { id: 'batch-mf-2', title: 'product B', star_rating: 3 },
    ]),
    requestPath: '/solr/testcollection/update/json/docs?commit=true',
    solrSchema: {
      fields: { title: { type: 'text_general' }, star_rating: { type: 'pint' } },
    },
    opensearchMapping: {
      properties: { title: { type: 'text' }, star_rating: { type: 'integer' } },
    },
    assertionRules: UPDATE_RESPONSE_RULES,
  }),

  // --- Bulk delete ---

  solrTest('bulk-delete-by-ids', {
    description: 'Bulk delete by array of IDs',
    documents: [
      { id: 'bdel-1', title: 'remove' },
      { id: 'bdel-2', title: 'remove' },
      { id: 'bdel-3', title: 'keep' },
    ],
    method: 'POST',
    requestBody: JSON.stringify({ delete: ['bdel-1', 'bdel-2'] }),
    requestPath: '/solr/testcollection/update?commit=true',
    solrSchema: { fields: { title: { type: 'text_general' } } },
    opensearchMapping: { properties: { title: { type: 'text' } } },
    assertionRules: UPDATE_RESPONSE_RULES,
  }),

  solrTest('bulk-delete-single-id-array', {
    description: 'Bulk delete with single-element ID array',
    documents: [{ id: 'bdel-single-1', title: 'gone' }],
    method: 'POST',
    requestBody: JSON.stringify({ delete: ['bdel-single-1'] }),
    requestPath: '/solr/testcollection/update?commit=true',
    solrSchema: { fields: { title: { type: 'text_general' } } },
    opensearchMapping: { properties: { title: { type: 'text' } } },
    assertionRules: UPDATE_RESPONSE_RULES,
  }),

  solrTest('bulk-delete-nonexistent-ids', {
    description: 'Bulk delete of non-existent IDs returns success',
    documents: [{ id: 'bdel-keep', title: 'safe' }],
    method: 'POST',
    requestBody: JSON.stringify({ delete: ['no-such-1', 'no-such-2'] }),
    requestPath: '/solr/testcollection/update?commit=true',
    solrSchema: { fields: { title: { type: 'text_general' } } },
    opensearchMapping: { properties: { title: { type: 'text' } } },
    assertionRules: UPDATE_RESPONSE_RULES,
  }),

  // --- Mixed commands ---

  solrTest('mixed-add-and-delete', {
    description: 'Mixed add + delete in single request',
    documents: [{ id: 'mix-old', title: 'to delete' }],
    method: 'POST',
    requestBody: JSON.stringify({
      add: [{ doc: { id: 'mix-new', title: 'fresh' } }],
      delete: ['mix-old'],
    }),
    requestPath: '/solr/testcollection/update?commit=true',
    solrSchema: { fields: { title: { type: 'text_general' } } },
    opensearchMapping: { properties: { title: { type: 'text' } } },
    assertionRules: UPDATE_RESPONSE_RULES,
  }),

  solrTest('mixed-add-delete-commit', {
    description: 'Mixed add + delete + commit in single request',
    documents: [{ id: 'mixc-old', title: 'old doc' }],
    method: 'POST',
    requestBody: JSON.stringify({
      add: [{ doc: { id: 'mixc-new', title: 'new doc' } }],
      delete: ['mixc-old'],
      commit: {},
    }),
    requestPath: '/solr/testcollection/update',
    solrSchema: { fields: { title: { type: 'text_general' } } },
    opensearchMapping: { properties: { title: { type: 'text' } } },
    assertionRules: UPDATE_RESPONSE_RULES,
  }),

  solrTest('mixed-single-add-single-delete', {
    description: 'Mixed single add (non-array) + single delete-by-id (non-array)',
    documents: [{ id: 'mixs-old', title: 'remove' }],
    method: 'POST',
    requestBody: JSON.stringify({
      add: { doc: { id: 'mixs-new', title: 'added' } },
      delete: { id: 'mixs-old' },
    }),
    requestPath: '/solr/testcollection/update?commit=true',
    solrSchema: { fields: { title: { type: 'text_general' } } },
    opensearchMapping: { properties: { title: { type: 'text' } } },
    assertionRules: UPDATE_RESPONSE_RULES,
  }),

  // --- Commit ---

  solrTest('commit-body-command', {
    description: 'Standalone commit via {"commit":{}} body',
    documents: [{ id: 'commit-test', title: 'should be visible' }],
    method: 'POST',
    requestBody: JSON.stringify({ commit: {} }),
    requestPath: '/solr/testcollection/update',
    solrSchema: { fields: { title: { type: 'text_general' } } },
    opensearchMapping: { properties: { title: { type: 'text' } } },
    assertionRules: UPDATE_RESPONSE_RULES,
  }),

  solrTest('commit-soft-commit', {
    description: 'Soft commit via {"commit":{"softCommit":true}}',
    documents: [{ id: 'soft-commit-test', title: 'soft visible' }],
    method: 'POST',
    requestBody: JSON.stringify({ commit: { softCommit: true } }),
    requestPath: '/solr/testcollection/update',
    solrSchema: { fields: { title: { type: 'text_general' } } },
    opensearchMapping: { properties: { title: { type: 'text' } } },
    assertionRules: UPDATE_RESPONSE_RULES,
  }),

  // --- Error paths ---

  solrTest('batch-error-add-missing-id', {
    description: 'Batch add with doc missing id should return 500',
    documents: [],
    method: 'POST',
    requestBody: JSON.stringify([{ title: 'no id' }, { id: '2', title: 'has id' }]),
    requestPath: '/solr/testcollection/update/json/docs',
    expectedStatusCode: 500,
    expectedErrorContains: 'Request transform failed',
  }),

  solrTest('batch-error-empty-array', {
    description: 'Batch add with empty array should return 500',
    documents: [],
    method: 'POST',
    requestBody: JSON.stringify([]),
    requestPath: '/solr/testcollection/update/json/docs',
    expectedStatusCode: 500,
    expectedErrorContains: 'Request transform failed',
  }),

  solrTest('batch-error-add-with-boost', {
    description: 'Batch add with boost in one element should return 500',
    documents: [],
    method: 'POST',
    requestBody: JSON.stringify({
      add: [{ doc: { id: '1', title: 'x' }, boost: 2 }],
    }),
    requestPath: '/solr/testcollection/update',
    expectedStatusCode: 500,
    expectedErrorContains: 'Request transform failed',
  }),

  // --- Additional batch coverage ---

  solrTest('batch-add-command-format-with-commit', {
    description: 'Batch add via {"add":[...]} with commit=true',
    documents: [],
    method: 'POST',
    requestBody: JSON.stringify({
      add: [
        { doc: { id: 'cmd-commit-1', title: 'one' } },
        { doc: { id: 'cmd-commit-2', title: 'two' } },
      ],
    }),
    requestPath: '/solr/testcollection/update?commit=true',
    solrSchema: { fields: { title: { type: 'text_general' } } },
    opensearchMapping: { properties: { title: { type: 'text' } } },
    assertionRules: UPDATE_RESPONSE_RULES,
  }),

  solrTest('bulk-delete-with-commit', {
    description: 'Bulk delete by IDs with commit=true',
    documents: [
      { id: 'bdel-c-1', title: 'gone' },
      { id: 'bdel-c-2', title: 'gone' },
    ],
    method: 'POST',
    requestBody: JSON.stringify({ delete: ['bdel-c-1', 'bdel-c-2'] }),
    requestPath: '/solr/testcollection/update?commit=true',
    solrSchema: { fields: { title: { type: 'text_general' } } },
    opensearchMapping: { properties: { title: { type: 'text' } } },
    assertionRules: UPDATE_RESPONSE_RULES,
  }),

  // --- Additional mixed mode coverage ---

  solrTest('mixed-multiple-adds-multiple-deletes', {
    description: 'Mixed: multiple adds + multiple deletes in one request',
    documents: [
      { id: 'mix-lg-del-1', title: 'remove' },
      { id: 'mix-lg-del-2', title: 'remove' },
      { id: 'mix-lg-del-3', title: 'remove' },
    ],
    method: 'POST',
    requestBody: JSON.stringify({
      add: [
        { doc: { id: 'mix-lg-add-1', title: 'new one' } },
        { doc: { id: 'mix-lg-add-2', title: 'new two' } },
        { doc: { id: 'mix-lg-add-3', title: 'new three' } },
      ],
      delete: ['mix-lg-del-1', 'mix-lg-del-2', 'mix-lg-del-3'],
    }),
    requestPath: '/solr/testcollection/update?commit=true',
    solrSchema: { fields: { title: { type: 'text_general' } } },
    opensearchMapping: { properties: { title: { type: 'text' } } },
    assertionRules: UPDATE_RESPONSE_RULES,
  }),

  solrTest('mixed-single-add-array-delete', {
    description: 'Mixed: single add (non-array) + array delete',
    documents: [
      { id: 'mix-sad-1', title: 'remove' },
      { id: 'mix-sad-2', title: 'remove' },
    ],
    method: 'POST',
    requestBody: JSON.stringify({
      add: { doc: { id: 'mix-sad-new', title: 'added' } },
      delete: ['mix-sad-1', 'mix-sad-2'],
    }),
    requestPath: '/solr/testcollection/update?commit=true',
    solrSchema: { fields: { title: { type: 'text_general' } } },
    opensearchMapping: { properties: { title: { type: 'text' } } },
    assertionRules: UPDATE_RESPONSE_RULES,
  }),

  solrTest('mixed-array-add-single-delete', {
    description: 'Mixed: array add + single delete-by-id (non-array)',
    documents: [{ id: 'mix-asd-old', title: 'remove' }],
    method: 'POST',
    requestBody: JSON.stringify({
      add: [
        { doc: { id: 'mix-asd-1', title: 'new' } },
        { doc: { id: 'mix-asd-2', title: 'also new' } },
      ],
      delete: { id: 'mix-asd-old' },
    }),
    requestPath: '/solr/testcollection/update?commit=true',
    solrSchema: { fields: { title: { type: 'text_general' } } },
    opensearchMapping: { properties: { title: { type: 'text' } } },
    assertionRules: UPDATE_RESPONSE_RULES,
  }),
];
