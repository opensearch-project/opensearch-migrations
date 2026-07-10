/**
 * Aggregate OpenSearch `_bulk` response into a Solr-shaped update response.
 *
 * ## Design decisions
 *
 * ### Partial-failure mapping (BULK-PARTIAL-FAILURE)
 *
 * Solr's default update semantics are strict: if any document in a batch fails,
 * the entire batch is rejected and no documents are applied. This is effectively
 * transactional — Solr rolls back the partial state on failure.
 *
 * OpenSearch's `_bulk` API has no such rollback. Successfully-indexed documents
 * remain in the index even if later items fail. See LIMITATIONS.md
 * BULK-PARTIAL-FAILURE for the full discussion of this behavioral difference.
 *
 * OpenSearch's `_bulk` API is always partial-success — the top-level `errors`
 * flag indicates whether *any* item failed, and each `items[]` entry carries
 * its own `status` and optional `error` block.
 *
 * We map conservatively:
 *   - All items succeeded (status < 400)  → `responseHeader.status = 0`
 *   - Any item failed                     → `responseHeader.status = 1`
 *                                           + `errors` array with per-item details
 *
 * The aggregated `errors` array gives clients enough information to retry
 * individual failures without forcing the shim to synthesize a fake "batch
 * failed" error for Solr compatibility. See LIMITATIONS shortcode
 * BULK-PARTIAL-FAILURE for the full discussion.
 *
 * ### QTime
 *
 * OpenSearch's `_bulk` response includes a `took` field (milliseconds). We
 * surface this as `QTime` — unlike the `_doc` API, which does not, so
 * single-doc aggregation uses `QTime: 0`.
 */
import type { MicroTransform } from '../../pipeline';
import type { ResponseContext, JavaMap } from '../../context';

/** Per-item error in the Solr-shaped aggregated response. */
export interface BulkAggregatedError {
  index: string | undefined;
  id: string | undefined;
  status: number | undefined;
  type: string | undefined;
  reason: string | undefined;
}

/**
 * Match predicate for the `_bulk` response shape. OpenSearch always returns
 * `{took, errors, items}` — `items` is the unambiguous marker.
 */
export function isBulkResponse(body: JavaMap): boolean {
  return body != null && typeof body.has === 'function'
    && body.has('items') && body.has('took');
}

/**
 * Walk `items[]` from an OpenSearch `_bulk` response and compute an overall
 * status plus a flat list of per-item errors.
 */
function aggregateItems(items: Iterable<unknown>): { hasFailure: boolean; errors: BulkAggregatedError[] } {
  const errors: BulkAggregatedError[] = [];
  let hasFailure = false;
  for (const raw of items) {
    const itemError = extractItemError(raw);
    if (itemError) {
      hasFailure = true;
      errors.push(itemError);
    }
  }
  return { hasFailure, errors };
}

/**
 * Inspect one `_bulk` items[] entry and return an aggregated-error record if
 * the item failed; otherwise `null`. Each entry has the shape
 * `{<op>: {_index, _id, status, error?}}` with exactly one op key.
 */
function extractItemError(raw: unknown): BulkAggregatedError | null {
  const entry = toMapLike(raw);
  if (!entry) return null;
  const detail = firstOpDetail(entry);
  if (!detail) return null;
  const status = toNumber(detail.get('status'));
  const errorDetail = toMapLike(detail.get('error'));
  const failed = (status != null && status >= 400) || errorDetail != null;
  if (!failed) return null;
  return {
    index: toStringOrUndefined(detail.get('_index')),
    id: toStringOrUndefined(detail.get('_id')),
    status: status,
    type: errorDetail ? toStringOrUndefined(errorDetail.get('type')) : undefined,
    reason: errorDetail ? toStringOrUndefined(errorDetail.get('reason')) : undefined,
  };
}

/** Return the detail map for the first (and only) op key in an items[] entry. */
function firstOpDetail(entry: JavaMap): JavaMap | null {
  const iter = entry.keys()[Symbol.iterator]();
  const first = iter.next();
  if (first.done) return null;
  return toMapLike(entry.get(first.value));
}

function toMapLike(v: unknown): JavaMap | null {
  if (v != null && typeof (v as any).get === 'function' && typeof (v as any).keys === 'function') {
    return v as JavaMap;
  }
  return null;
}

function toNumber(v: unknown): number | undefined {
  if (typeof v === 'number') return v;
  if (typeof v === 'string' && v !== '') {
    const n = Number(v);
    return Number.isFinite(n) ? n : undefined;
  }
  return undefined;
}

function toStringOrUndefined(v: unknown): string | undefined {
  if (v == null) return undefined;
  // Only convert values with a well-defined string representation. Plain
  // objects would stringify as "[object Object]", which is never useful here
  // and is what Sonar's S3854 flags.
  if (typeof v === 'string') return v !== '' ? v : undefined;
  if (typeof v === 'number' || typeof v === 'boolean' || typeof v === 'bigint') {
    return String(v);
  }
  return undefined;
}

/**
 * Response transform for an OpenSearch `_bulk` API response.
 *
 * Rewrites `ctx.responseBody` in place to the Solr-shaped form:
 *   {
 *     responseHeader: { status, QTime },
 *     errors?: [ { index, id, status, type, reason }, ... ]
 *   }
 */
export const response: MicroTransform<ResponseContext> = {
  name: 'bulk-response',
  match: (ctx) => isBulkResponse(ctx.responseBody),
  apply: (ctx) => {
    const body = ctx.responseBody;
    const took = toNumber(body.get('took')) ?? 0;

    const itemsRaw = body.get('items');
    const items: Iterable<unknown> = isIterable(itemsRaw) ? (itemsRaw as Iterable<unknown>) : [];
    const { hasFailure, errors } = aggregateItems(items);

    // Wipe the OpenSearch shape before writing the Solr one. Snapshot the keys
    // to avoid mutating-while-iterating on the underlying Java Map.
    const keys = Array.from(body.keys());
    for (const key of keys) {
      body.delete(key);
    }

    body.set(
      'responseHeader',
      new Map<string, unknown>([
        ['status', hasFailure ? 1 : 0],
        ['QTime', took],
      ]),
    );

    if (errors.length > 0) {
      body.set('errors', errors.map(serializeError));
    }
  },
};

function isIterable(v: unknown): boolean {
  return v != null && typeof (v as any)[Symbol.iterator] === 'function';
}

/** Convert an aggregated-error record to a Map so the result round-trips through Jackson. */
function serializeError(err: BulkAggregatedError): Map<string, unknown> {
  const m = new Map<string, unknown>();
  if (err.index !== undefined) m.set('index', err.index);
  if (err.id !== undefined) m.set('id', err.id);
  if (err.status !== undefined) m.set('status', err.status);
  if (err.type !== undefined) m.set('type', err.type);
  if (err.reason !== undefined) m.set('reason', err.reason);
  return m;
}
