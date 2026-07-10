/**
 * Stateful sequence helpers — Phase 2.
 *
 * Each exported function performs one step of a create → update → query → delete
 * lifecycle for a single document. `runSequence` composes all four steps and
 * bails on create failure (nothing to update/query/delete if the doc wasn't written).
 *
 * connParams must come from pinned() or spread() in lib/connection-control.js.
 * Tags on each request correspond to unique Prometheus label values so per-step
 * latency is visible in Grafana without any dashboard changes.
 */

import http from 'k6/http';
import { check } from 'k6';
import { randomDocument } from './documents.js';

// __VU and __ITER are k6 built-in globals; unique per VU + iteration combination.
function generateId() {
  return `seq-${__VU}-${__ITER}`;
}

export function createDocument(proxyUrl, index, connParams) {
  const id = generateId();
  const res = http.put(
    `${proxyUrl}/${index}/_doc/${id}`,
    JSON.stringify(randomDocument()),
    { ...connParams, tags: { name: 'seq_create' } },
  );
  check(res, { 'seq create (201)': (r) => r.status === 201 });
  return { id, res };
}

export function updateDocument(proxyUrl, index, id, connParams) {
  const res = http.post(
    `${proxyUrl}/${index}/_update/${id}`,
    JSON.stringify({ doc: { total_amount: parseFloat((Math.random() * 45 + 5).toFixed(2)) } }),
    { ...connParams, tags: { name: 'seq_update' } },
  );
  check(res, { 'seq update (200)': (r) => r.status === 200 });
  return res;
}

export function queryById(proxyUrl, index, id, connParams) {
  const res = http.get(
    `${proxyUrl}/${index}/_doc/${id}`,
    { ...connParams, tags: { name: 'seq_query' } },
  );
  check(res, { 'seq query (200)': (r) => r.status === 200 });
  return res;
}

export function deleteDocument(proxyUrl, index, id, connParams) {
  const res = http.del(
    `${proxyUrl}/${index}/_doc/${id}`,
    null,
    { ...connParams, tags: { name: 'seq_delete' } },
  );
  check(res, { 'seq delete (200)': (r) => r.status === 200 });
  return res;
}

/**
 * Run a full create → update → query → delete sequence for one ephemeral document.
 * Returns { success: boolean, id: string }.
 * On create failure the remaining steps are skipped (no id to act on).
 */
export function runSequence(proxyUrl, index, connParams) {
  const { id, res: createRes } = createDocument(proxyUrl, index, connParams);
  if (createRes.status !== 201) {
    return { success: false, id };
  }
  updateDocument(proxyUrl, index, id, connParams);
  queryById(proxyUrl, index, id, connParams);
  deleteDocument(proxyUrl, index, id, connParams);
  return { success: true, id };
}
