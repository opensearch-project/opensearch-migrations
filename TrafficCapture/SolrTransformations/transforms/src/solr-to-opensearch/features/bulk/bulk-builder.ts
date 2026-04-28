/**
 * Shared helpers for constructing OpenSearch `_bulk` API requests from Solr
 * command-level operations (index / create / update / delete).
 *
 * No feature consumes these helpers yet — they will be used by PR 2 (batch add,
 * bulk delete), PR 4 (mixed commands), and any future handler that needs to
 * produce multiple OpenSearch operations from a single Solr request.
 *
 * ## Framework alignment
 *
 * - NDJSON payloads are written under `payload.inlinedJsonSequenceBodies`, the
 *   canonical key defined in `JsonKeysForHttpMessage.INLINED_NDJSON_BODIES_DOCUMENT_KEY`.
 * - The Java side (`HttpMessageUtil.extractBodyString`) materializes the list
 *   as one JSON document per line with a trailing newline — matching both the
 *   traffic replayer's `NettyJsonBodySerializeHandler` and OpenSearch's `_bulk`
 *   API contract.
 * - `Content-Type: application/x-ndjson` is set by deleting any case-variant
 *   sibling first, then adding the header — the shim's Java proxy uses
 *   `headers.add()` rather than `set()`, so casing-variant duplicates can
 *   otherwise leak through.
 */
import type { RequestContext } from '../../context';

/** Supported OpenSearch `_bulk` operation types. */
export type BulkOp = 'index' | 'create' | 'update' | 'delete';

/** Arguments to {@link buildBulkAction}. */
export interface BulkActionInput {
  /** OpenSearch target index — typically derived from `ctx.collection`. */
  index: string;
  /** Document id. Required for `create`/`update`/`delete`; optional for `index`. */
  id?: string | number;
  /**
   * Document body:
   *   - `index` / `create`: the full source document (required)
   *   - `update`: an update payload (`{doc: {...}}`, `{script: {...}}`, etc.) — required
   *   - `delete`: must be omitted
   */
  doc?: unknown;
}

/**
 * Build the NDJSON entries for a single `_bulk` operation.
 *
 * Returns an array that caller flattens into the full NDJSON sequence:
 *   - `index` / `create`: `[{index|create: {_index, _id?}}, doc]` — 2 entries
 *   - `update`:           `[{update: {_index, _id}}, updatePayload]` — 2 entries
 *   - `delete`:           `[{delete: {_index, _id}}]` — 1 entry
 *
 * The caller is responsible for `encodeURIComponent` on ids that reach the URI,
 * but action metadata carries raw values — OpenSearch JSON-encodes them itself.
 */
export function buildBulkAction(op: BulkOp, input: BulkActionInput): Map<string, unknown>[] {
  if (!input.index) {
    throw new Error(`[bulk-builder] ${op}: "index" is required`);
  }
  const meta = buildMeta(input);

  switch (op) {
    case 'delete':
      return buildDelete(meta, input);
    case 'update':
      return buildUpdate(meta, input);
    case 'create':
      return buildCreate(meta, input);
    case 'index':
      return buildIndex(meta, input);
    default: {
      // Exhaustiveness: TypeScript catches missing arms; this guards a runtime
      // caller coming from plain JS.
      const exhaustive: never = op;
      throw new Error(`[bulk-builder] unsupported op '${exhaustive as string}'`);
    }
  }
}

function buildMeta(input: BulkActionInput): Map<string, unknown> {
  const meta = new Map<string, unknown>();
  meta.set('_index', input.index);
  if (input.id != null && input.id !== '') {
    meta.set('_id', String(input.id));
  }
  return meta;
}

function buildDelete(meta: Map<string, unknown>, input: BulkActionInput): Map<string, unknown>[] {
  if (!meta.has('_id')) {
    throw new Error('[bulk-builder] delete: "id" is required');
  }
  if (input.doc !== undefined) {
    throw new Error('[bulk-builder] delete: must not include "doc"');
  }
  return [new Map<string, unknown>([['delete', meta]])];
}

function buildUpdate(meta: Map<string, unknown>, input: BulkActionInput): Map<string, unknown>[] {
  if (!meta.has('_id')) {
    throw new Error('[bulk-builder] update: "id" is required');
  }
  if (input.doc == null) {
    throw new Error('[bulk-builder] update: "doc" is required (e.g. { doc: {...} } or { script: ... })');
  }
  return [
    new Map<string, unknown>([['update', meta]]),
    input.doc as Map<string, unknown>,
  ];
}

function buildCreate(meta: Map<string, unknown>, input: BulkActionInput): Map<string, unknown>[] {
  if (!meta.has('_id')) {
    throw new Error('[bulk-builder] create: "id" is required');
  }
  if (input.doc == null) {
    throw new Error('[bulk-builder] create: "doc" is required');
  }
  return [
    new Map<string, unknown>([['create', meta]]),
    input.doc as Map<string, unknown>,
  ];
}

function buildIndex(meta: Map<string, unknown>, input: BulkActionInput): Map<string, unknown>[] {
  if (input.doc == null) {
    throw new Error('[bulk-builder] index: "doc" is required');
  }
  // For `index`, _id is optional — OpenSearch auto-generates when omitted.
  return [
    new Map<string, unknown>([['index', meta]]),
    input.doc as Map<string, unknown>,
  ];
}

/** Options accepted by {@link setBulkRequest}. */
export interface SetBulkRequestOptions {
  /**
   * When true, appends `?refresh=true` to the `_bulk` URI — used for Solr
   * `commit=true` / `commitWithin` semantics (immediate visibility).
   */
  refresh?: boolean;
}

/**
 * Mutate a {@link RequestContext} to emit a `_bulk` request to OpenSearch.
 *
 * Actions should be the flattened concatenation of `buildBulkAction(...)`
 * results. Side effects:
 *   - URI rewritten to `/{collection}/_bulk` (+ optional `?refresh=true`)
 *   - Method set to `POST`
 *   - `Content-Type` replaced with `application/x-ndjson` (casing-safe)
 *   - `payload.inlinedJsonSequenceBodies` set to `actions`
 *   - `payload.inlinedJsonBody` cleared if present (single-body invariant)
 *   - `ctx.body` cleared so `request.transform.ts` does not re-populate
 *     `inlinedJsonBody` from stale state
 */
export function setBulkRequest(
  ctx: RequestContext,
  collection: string,
  actions: Map<string, unknown>[],
  options: SetBulkRequestOptions = {},
): void {
  if (!collection) {
    throw new Error('[bulk-builder] setBulkRequest: collection is required');
  }
  if (!Array.isArray(actions) || actions.length === 0) {
    throw new Error('[bulk-builder] setBulkRequest: actions must be a non-empty array');
  }

  const suffix = options.refresh ? '?refresh=true' : '';
  ctx.msg.set('URI', `/${collection}/_bulk${suffix}`);
  ctx.msg.set('method', 'POST');

  const headers = ctx.msg.get('headers');
  if (headers) {
    // The Java proxy uses headers.add() (not set()) — strip any casing variant
    // before writing to avoid duplicate Content-Type headers on the wire.
    headers.delete('Content-Type');
    headers.delete('content-type');
    headers.set('content-type', 'application/x-ndjson');
  }

  let payload = ctx.msg.get('payload');
  if (!payload) {
    payload = new Map();
    ctx.msg.set('payload', payload);
  }
  // Enforce single-body invariant: exactly one of inlinedJsonBody /
  // inlinedJsonSequenceBodies is set at a time (matches replayer contract).
  payload.delete('inlinedJsonBody');
  payload.set('inlinedJsonSequenceBodies', actions);

  // Clear ctx.body so request.transform.ts's post-pipeline write does not
  // re-populate inlinedJsonBody from an earlier state.
  ctx.body = new Map();
}
