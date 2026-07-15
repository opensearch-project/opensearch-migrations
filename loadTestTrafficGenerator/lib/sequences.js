/**
 * Stateful sequence helpers — Phase 2.
 *
 * Each internal function performs one step of a create → update → query → delete
 * lifecycle for a single document. `runSequence` composes all four steps and
 * bails on create failure (nothing to update/query/delete if the doc wasn't written).
 *
 * connParams must come from pinned() or spread() in lib/connection-control.js.
 * docFns must provide { randomDocument, randomUpdateBody } from the active scenario's
 * documents module (e.g. lib/data/nyc_taxis/documents.js or lib/data/logs_data/documents.js).
 * Tags on each request correspond to unique Prometheus label values so per-step
 * latency is visible in Grafana without any dashboard changes.
 */

import http from 'k6/http';
import { check } from 'k6';

// __VU and __ITER are k6 built-in globals; unique per VU + iteration combination.
function generateId() {
  return `seq-${__VU}-${__ITER}`;
}

function createDocument(proxyUrl, index, connParams, randomDocument) {
  const id = generateId();
  const res = http.put(
    `${proxyUrl}/${index}/_doc/${id}`,
    JSON.stringify(randomDocument()),
    { ...connParams, tags: { name: 'seq_create' } },
  );
  check(res, { 'seq create (201)': (r) => r.status === 201 });
  return { id, res };
}

function updateDocument(proxyUrl, index, id, connParams, randomUpdateBody) {
  const res = http.post(
    `${proxyUrl}/${index}/_update/${id}`,
    JSON.stringify({ doc: randomUpdateBody() }),
    { ...connParams, tags: { name: 'seq_update' } },
  );
  check(res, { 'seq update (200)': (r) => r.status === 200 });
  return res;
}

function queryById(proxyUrl, index, id, connParams) {
  const res = http.get(
    `${proxyUrl}/${index}/_doc/${id}`,
    { ...connParams, tags: { name: 'seq_query' } },
  );
  check(res, { 'seq query (200)': (r) => r.status === 200 });
  return res;
}

function deleteDocument(proxyUrl, index, id, connParams) {
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
 *
 * @param {string} proxyUrl
 * @param {string} index
 * @param {object} connParams - from pinned() or spread()
 * @param {{ randomDocument: () => object, randomUpdateBody: () => object }} docFns
 */
export function runSequence(proxyUrl, index, connParams, { randomDocument, randomUpdateBody }) {
  const { id, res: createRes } = createDocument(proxyUrl, index, connParams, randomDocument);
  if (createRes.status !== 201) {
    return { success: false, id };
  }
  updateDocument(proxyUrl, index, id, connParams, randomUpdateBody);
  queryById(proxyUrl, index, id, connParams);
  deleteDocument(proxyUrl, index, id, connParams);
  return { success: true, id };
}
