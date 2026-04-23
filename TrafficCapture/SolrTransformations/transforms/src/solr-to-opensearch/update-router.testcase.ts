/**
 * E2E test cases for /update command dispatcher — delete-by-id and add-via-update.
 */
import { solrTest, SOLR_INTERNAL_RULES } from '../test-types';
import type { TestCase } from '../test-types';

const UPDATE_RESPONSE_RULES = [
  { path: '$.responseHeader.QTime', rule: 'ignore' as const, reason: 'Timing varies' },
  { path: '$.responseHeader.params', rule: 'ignore' as const, reason: 'Solr echoes params, proxy does not' },
];

export const testCases: TestCase[] = [
  // --- delete-by-id: happy path ---

  solrTest('update-cmd-delete-by-id', {
    description: 'Delete existing document via /update with delete command',
    documents: [{ id: '1', title: 'to be deleted' }],
    method: 'POST',
    requestBody: JSON.stringify({ delete: { id: '1' } }),
    requestPath: '/solr/testcollection/update?commit=true',
    solrSchema: {
      fields: { title: { type: 'text_general' } },
    },
    opensearchMapping: {
      properties: { title: { type: 'text' } },
    },
    assertionRules: UPDATE_RESPONSE_RULES,
  }),

  solrTest('update-cmd-delete-special-chars-id', {
    description: 'Delete document with special characters in id',
    documents: [{ id: 'doc-with-dashes', title: 'special' }],
    method: 'POST',
    requestBody: JSON.stringify({ delete: { id: 'doc-with-dashes' } }),
    requestPath: '/solr/testcollection/update?commit=true',
    solrSchema: {
      fields: { title: { type: 'text_general' } },
    },
    opensearchMapping: {
      properties: { title: { type: 'text' } },
    },
    assertionRules: UPDATE_RESPONSE_RULES,
  }),

  solrTest('update-cmd-delete-nonexistent', {
    description: 'Delete non-existent document should still return success',
    documents: [{ id: '1', title: 'keep this' }],
    method: 'POST',
    requestBody: JSON.stringify({ delete: { id: 'does-not-exist' } }),
    requestPath: '/solr/testcollection/update?commit=true',
    solrSchema: {
      fields: { title: { type: 'text_general' } },
    },
    opensearchMapping: {
      properties: { title: { type: 'text' } },
    },
    assertionRules: UPDATE_RESPONSE_RULES,
  }),

  // --- add via /update: happy path ---

  solrTest('update-cmd-add-single-doc', {
    description: 'Add document via /update with add command',
    documents: [],
    method: 'POST',
    requestBody: JSON.stringify({ add: { doc: { id: '1', title: 'added via update' } } }),
    requestPath: '/solr/testcollection/update?commit=true',
    solrSchema: {
      fields: { title: { type: 'text_general' } },
    },
    opensearchMapping: {
      properties: { title: { type: 'text' } },
    },
    assertionRules: UPDATE_RESPONSE_RULES,
  }),

  solrTest('update-cmd-add-multiple-fields', {
    description: 'Add document with multiple field types via /update',
    documents: [],
    method: 'POST',
    requestBody: JSON.stringify({ add: { doc: { id: '2', title: 'multi', price: 19.99, inStock: true } } }),
    requestPath: '/solr/testcollection/update?commit=true',
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        price: { type: 'pfloat' },
        inStock: { type: 'boolean' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        price: { type: 'float' },
        inStock: { type: 'boolean' },
      },
    },
    assertionRules: UPDATE_RESPONSE_RULES,
  }),

  // --- error paths ---

  solrTest('update-cmd-error-delete-by-query', {
    description: 'Delete-by-query should return 500',
    documents: [{ id: '1', title: 'test' }],
    method: 'POST',
    requestBody: JSON.stringify({ delete: { query: 'title:test' } }),
    requestPath: '/solr/testcollection/update',
    expectedStatusCode: 500,
    expectedErrorContains: 'Request transform failed',
  }),

  solrTest('update-cmd-error-mixed-commands', {
    description: 'Mixed commands should return 500',
    documents: [],
    method: 'POST',
    requestBody: JSON.stringify({ delete: { id: '1' }, add: { doc: { id: '2', title: 'x' } } }),
    requestPath: '/solr/testcollection/update',
    expectedStatusCode: 500,
    expectedErrorContains: 'Request transform failed',
  }),

  solrTest('update-cmd-error-unsupported-commit', {
    description: 'Standalone commit command should return 500',
    documents: [],
    method: 'POST',
    requestBody: JSON.stringify({ commit: {} }),
    requestPath: '/solr/testcollection/update',
    expectedStatusCode: 500,
    expectedErrorContains: 'Request transform failed',
  }),

  solrTest('update-cmd-error-empty-body', {
    description: 'Empty body on /update should return 500',
    documents: [],
    method: 'POST',
    requestBody: JSON.stringify({}),
    requestPath: '/solr/testcollection/update',
    expectedStatusCode: 500,
    expectedErrorContains: 'Request transform failed',
  }),

  solrTest('update-cmd-error-delete-missing-id', {
    description: 'Delete without id field should return 500',
    documents: [],
    method: 'POST',
    requestBody: JSON.stringify({ delete: {} }),
    requestPath: '/solr/testcollection/update',
    expectedStatusCode: 500,
    expectedErrorContains: 'Request transform failed',
  }),

  solrTest('update-cmd-error-array-deletes', {
    description: 'Array of deletes should return 500 (bulk not supported)',
    documents: [],
    method: 'POST',
    requestBody: JSON.stringify({ delete: [{ id: '1' }, { id: '2' }] }),
    requestPath: '/solr/testcollection/update',
    expectedStatusCode: 500,
    expectedErrorContains: 'Request transform failed',
  }),

  solrTest('update-cmd-error-add-with-boost', {
    description: 'Add with document-level boost should return 500',
    documents: [],
    method: 'POST',
    requestBody: JSON.stringify({ add: { doc: { id: '1', title: 'test' }, boost: 2 } }),
    requestPath: '/solr/testcollection/update',
    expectedStatusCode: 500,
    expectedErrorContains: 'Request transform failed',
  }),

  solrTest('update-cmd-error-add-overwrite-false', {
    description: 'Add with overwrite=false should return 500',
    documents: [],
    method: 'POST',
    requestBody: JSON.stringify({ add: { doc: { id: '1', title: 'test' }, overwrite: false } }),
    requestPath: '/solr/testcollection/update',
    expectedStatusCode: 500,
    expectedErrorContains: 'Request transform failed',
  }),

  solrTest('update-cmd-error-delete-with-route', {
    description: 'Delete with _route_ field should return 500',
    documents: [],
    method: 'POST',
    requestBody: JSON.stringify({ delete: { id: '1', _route_: 'shard1' } }),
    requestPath: '/solr/testcollection/update',
    expectedStatusCode: 500,
    expectedErrorContains: 'Request transform failed',
  }),

  // --- Side-effect verification tests ---

  solrTest('update-cmd-add-then-query', {
    description: 'Add a doc via /update, then verify it exists via select',
    documents: [],
    method: 'POST',
    requestBody: JSON.stringify({ add: { doc: { id: 'verify-add-1', title: 'added and verified' } } }),
    requestPath: '/solr/testcollection/update?commit=true',
    solrSchema: {
      fields: { title: { type: 'text_general' } },
    },
    opensearchMapping: {
      properties: { title: { type: 'text' } },
    },
    assertionRules: UPDATE_RESPONSE_RULES,
    requestSequence: [
      {
        requestPath: '/solr/testcollection/select?q=id:verify-add-1&wt=json',
        assertionRules: [
          ...SOLR_INTERNAL_RULES,
        ],
      },
    ],
  }),

  solrTest('update-cmd-delete-then-query', {
    description: 'Delete a doc via /update, then verify it is gone via select',
    documents: [{ id: 'verify-del-1', title: 'to be deleted' }],
    method: 'POST',
    requestBody: JSON.stringify({ delete: { id: 'verify-del-1' } }),
    requestPath: '/solr/testcollection/update?commit=true',
    solrSchema: {
      fields: { title: { type: 'text_general' } },
    },
    opensearchMapping: {
      properties: { title: { type: 'text' } },
    },
    assertionRules: UPDATE_RESPONSE_RULES,
    requestSequence: [
      {
        requestPath: '/solr/testcollection/select?q=id:verify-del-1&wt=json',
        assertionRules: [
          ...SOLR_INTERNAL_RULES,
        ],
      },
    ],
  }),
];
