/**
 * Test cases for Solr Standard Query Parser field queries → OpenSearch transformation.
 *
 * These tests validate the query-engine's ability to parse and transform
 * Solr's field:value syntax into OpenSearch match and exists queries.
 *
 * Adding a new test: just add a solrTest() entry below.
 * It automatically runs against every Solr version in matrix.config.ts.
 */
import { solrTest } from '../../../test-types';
import type { TestCase } from '../../../test-types';

export const testCases: TestCase[] = [
  // ───────────────────────────────────────────────────────────
  // Field queries (field:value → match query)
  // ───────────────────────────────────────────────────────────
    solrTest('query-term-single-field', {
        description: 'Simple field query on a text field',
        documents: [
            { id: '1', title: 'laptop', category: 'electronics' },
            { id: '2', title: 'phone', category: 'electronics' },
            { id: '3', title: 'shirt', category: 'clothing' },
        ],
        requestPath: '/solr/testcollection/select?q=category:electronics&wt=json',
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
    }),

  // ───────────────────────────────────────────────────────────
  // Existence queries (field:*)
  // ───────────────────────────────────────────────────────────
    solrTest('query-existence-field-exists', {
        description: 'Existence query matches documents where field has any value',
        documents: [
            { id: '1', title: 'laptop', category: 'electronics' },
            { id: '2', title: 'phone' },
            { id: '3', title: 'shirt', category: 'clothing' },
        ],
        requestPath: '/solr/testcollection/select?q=category:*&wt=json',
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
    }),
];
