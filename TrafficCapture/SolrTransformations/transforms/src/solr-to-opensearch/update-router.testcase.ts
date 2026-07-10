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

  // --- delete-by-query: happy path ---

  solrTest('update-cmd-delete-by-query-simple', {
    description: 'Delete documents matching a simple field query',
    documents: [
      { id: 'dbq-1', title: 'remove me' },
      { id: 'dbq-2', title: 'keep me' },
      { id: 'dbq-3', title: 'remove me' },
    ],
    method: 'POST',
    requestBody: JSON.stringify({ delete: { query: 'title:"remove me"' } }),
    requestPath: '/solr/testcollection/update?commit=true',
    solrSchema: {
      fields: { title: { type: 'text_general' } },
    },
    opensearchMapping: {
      properties: { title: { type: 'text' } },
    },
    assertionRules: UPDATE_RESPONSE_RULES,
  }),

  solrTest('update-cmd-delete-by-query-match-all', {
    description: 'Delete all documents with *:* query',
    documents: [
      { id: 'dbq-all-1', title: 'one' },
      { id: 'dbq-all-2', title: 'two' },
    ],
    method: 'POST',
    requestBody: JSON.stringify({ delete: { query: '*:*' } }),
    requestPath: '/solr/testcollection/update?commit=true',
    solrSchema: {
      fields: { title: { type: 'text_general' } },
    },
    opensearchMapping: {
      properties: { title: { type: 'text' } },
    },
    assertionRules: UPDATE_RESPONSE_RULES,
  }),

  solrTest('update-cmd-delete-by-query-no-match', {
    description: 'Delete-by-query with no matching documents returns success',
    documents: [{ id: 'dbq-keep', title: 'safe' }],
    method: 'POST',
    requestBody: JSON.stringify({ delete: { query: 'title:nonexistent' } }),
    requestPath: '/solr/testcollection/update?commit=true',
    solrSchema: {
      fields: { title: { type: 'text_general' } },
    },
    opensearchMapping: {
      properties: { title: { type: 'text' } },
    },
    assertionRules: UPDATE_RESPONSE_RULES,
  }),

  solrTest('update-cmd-delete-by-query-with-commit', {
    description: 'Delete-by-query with commit=true makes deletions immediately visible',
    documents: [
      { id: 'dbq-commit-1', title: 'visible' },
      { id: 'dbq-commit-2', title: 'visible' },
    ],
    method: 'POST',
    requestBody: JSON.stringify({ delete: { query: 'title:visible' } }),
    requestPath: '/solr/testcollection/update?commit=true',
    solrSchema: {
      fields: { title: { type: 'text_general' } },
    },
    opensearchMapping: {
      properties: { title: { type: 'text' } },
    },
    assertionRules: UPDATE_RESPONSE_RULES,
  }),

  solrTest('update-cmd-delete-by-query-boolean-and', {
    description: 'Delete-by-query with AND boolean query',
    documents: [
      { id: 'dbq-bool-1', title: 'alpha', category: 'draft' },
      { id: 'dbq-bool-2', title: 'alpha', category: 'published' },
      { id: 'dbq-bool-3', title: 'beta', category: 'draft' },
    ],
    method: 'POST',
    requestBody: JSON.stringify({ delete: { query: 'title:alpha AND category:draft' } }),
    requestPath: '/solr/testcollection/update?commit=true',
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        category: { type: 'text_general' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        category: { type: 'text' },
      },
    },
    assertionRules: UPDATE_RESPONSE_RULES,
  }),

  solrTest('update-cmd-delete-by-query-boolean-or', {
    description: 'Delete-by-query with OR boolean query',
    documents: [
      { id: 'dbq-or-1', title: 'remove' },
      { id: 'dbq-or-2', title: 'delete' },
      { id: 'dbq-or-3', title: 'keep' },
    ],
    method: 'POST',
    requestBody: JSON.stringify({ delete: { query: 'title:remove OR title:delete' } }),
    requestPath: '/solr/testcollection/update?commit=true',
    solrSchema: {
      fields: { title: { type: 'text_general' } },
    },
    opensearchMapping: {
      properties: { title: { type: 'text' } },
    },
    assertionRules: UPDATE_RESPONSE_RULES,
  }),

  solrTest('update-cmd-delete-by-query-wildcard', {
    description: 'Delete-by-query with wildcard query',
    documents: [
      { id: 'dbq-wc-1', title: 'temporary_file' },
      { id: 'dbq-wc-2', title: 'temporary_data' },
      { id: 'dbq-wc-3', title: 'permanent' },
    ],
    method: 'POST',
    requestBody: JSON.stringify({ delete: { query: 'title:temporary*' } }),
    requestPath: '/solr/testcollection/update?commit=true',
    solrSchema: {
      fields: { title: { type: 'text_general' } },
    },
    opensearchMapping: {
      properties: { title: { type: 'text' } },
    },
    assertionRules: UPDATE_RESPONSE_RULES,
  }),

  solrTest('update-cmd-delete-by-query-range', {
    description: 'Delete-by-query with range query on numeric field',
    documents: [
      { id: 'dbq-range-1', title: 'cheap', price: 5 },
      { id: 'dbq-range-2', title: 'mid', price: 50 },
      { id: 'dbq-range-3', title: 'expensive', price: 500 },
    ],
    method: 'POST',
    requestBody: JSON.stringify({ delete: { query: 'price:[100 TO *]' } }),
    requestPath: '/solr/testcollection/update?commit=true',
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        price: { type: 'pint' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        price: { type: 'integer' },
      },
    },
    assertionRules: UPDATE_RESPONSE_RULES,
  }),

  solrTest('update-cmd-delete-by-query-negation', {
    description: 'Delete-by-query with NOT query — delete everything except matching',
    documents: [
      { id: 'dbq-not-1', title: 'keep', category: 'important' },
      { id: 'dbq-not-2', title: 'remove', category: 'trash' },
      { id: 'dbq-not-3', title: 'also remove', category: 'junk' },
    ],
    method: 'POST',
    requestBody: JSON.stringify({ delete: { query: '*:* AND NOT category:important' } }),
    requestPath: '/solr/testcollection/update?commit=true',
    solrSchema: {
      fields: {
        title: { type: 'text_general' },
        category: { type: 'text_general' },
      },
    },
    opensearchMapping: {
      properties: {
        title: { type: 'text' },
        category: { type: 'text' },
      },
    },
    assertionRules: UPDATE_RESPONSE_RULES,
  }),

  solrTest('update-cmd-delete-by-query-no-commit', {
    description: 'Delete-by-query without commit param still succeeds (async visibility)',
    documents: [
      { id: 'dbq-nc-1', title: 'target' },
    ],
    method: 'POST',
    requestBody: JSON.stringify({ delete: { query: 'title:target' } }),
    requestPath: '/solr/testcollection/update',
    solrSchema: {
      fields: { title: { type: 'text_general' } },
    },
    opensearchMapping: {
      properties: { title: { type: 'text' } },
    },
    assertionRules: UPDATE_RESPONSE_RULES,
  }),

  // --- delete-by-query: error paths ---

  solrTest('update-cmd-error-delete-by-query-empty', {
    description: 'Delete-by-query with empty query should return 500',
    documents: [{ id: '1', title: 'test' }],
    method: 'POST',
    requestBody: JSON.stringify({ delete: { query: '' } }),
    requestPath: '/solr/testcollection/update',
    expectedStatusCode: 500,
    expectedErrorContains: 'Request transform failed',
  }),

  solrTest('update-cmd-error-delete-by-query-id-and-query', {
    description: 'Delete with both id and query should return 500 (mutually exclusive)',
    documents: [{ id: '1', title: 'test' }],
    method: 'POST',
    requestBody: JSON.stringify({ delete: { id: '1', query: 'title:test' } }),
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
