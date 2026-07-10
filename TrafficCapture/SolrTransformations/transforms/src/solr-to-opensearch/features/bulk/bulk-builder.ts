/**
 * Shared helpers for constructing OpenSearch `_bulk` API requests from Solr
 * command-level operations (index / create / update / delete).
 *
 * ## Framework alignment
 *
 * - NDJSON payloads are written under `payload.inlinedJsonSequenceBodies`, the
 *   canonical key defined in `JsonKeysForHttpMessage.INLINED_NDJSON_BODIES_DOCUMENT_KEY`.
 * - The Java side (`HttpMessageUtil.extractBodyString`) materializes the list
 *   as one JSON document per line with a trailing newline — matching both the
 *   traffic replayer's `NettyJsonBodySerializeHandler` and OpenSearch's `_bulk`
 *   API contract.
 * - `Content-Type: application/x-ndjson` is set by case-insensitively removing
 *   any existing Content-Type header before writing — the shim's Java proxy
 *   uses `headers.add()` rather than `set()`, so casing-variant duplicates can
 *   otherwise leak through.
 */
import type { RequestContext, JavaMap } from '../../context';

/** Supported OpenSearch `_bulk` operation types. */
export type BulkOp = 'index' | 'create' | 'update' | 'delete';

/** Document body type — a JavaMap (GraalVM polyglot Map) or a JS Map. */
type DocBody = JavaMap | Map<string, unknown>;

/** Arguments to {@link buildBulkAction}. */
export interface BulkActionInput {
  /** OpenSearch target index — typically derived from `ctx.collection`. */
  index: string;
  /** Document id. Required for `create`/`update`/`delete`; optional for `index`. */
  id?: string | number;
  /**
   * Document body — a Map representing the JSON document.
   *   - `index` / `create`: the full source document (required)
   *   - `update`: an update payload, e.g. `Map{doc → Map{field → value}}` (required)
   *   - `delete`: must be omitted
   */
  doc?: DocBody;
}

/**
 * Build the NDJSON entries for a single `_bulk` operation.
 *
 * Returns an array that the caller flattens into the full NDJSON sequence.
 * Each entry becomes one line in the NDJSON body after Java-side serialization.
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

/** Output: `[{"delete": {"_index": "mycore", "_id": "42"}}]` — one NDJSON line, no doc body. */
function buildDelete(meta: Map<string, unknown>, input: BulkActionInput): Map<string, unknown>[] {
  if (!meta.has('_id')) {
    throw new Error('[bulk-builder] delete: "id" is required');
  }
  if (input.doc !== undefined) {
    throw new Error('[bulk-builder] delete: must not include "doc"');
  }
  return [new Map<string, unknown>([['delete', meta]])];
}

/** Output: `[{"update": {"_index": "mycore", "_id": "1"}}, {"doc": {"title": "new"}}]` — two NDJSON lines. */
function buildUpdate(meta: Map<string, unknown>, input: BulkActionInput): Map<string, unknown>[] {
  if (!meta.has('_id')) {
    throw new Error('[bulk-builder] update: "id" is required');
  }
  if (input.doc == null) {
    throw new Error('[bulk-builder] update: "doc" is required (e.g. { doc: {...} } or { script: ... })');
  }
  return [new Map<string, unknown>([['update', meta]]), input.doc as Map<string, unknown>];
}

/** Output: `[{"create": {"_index": "mycore", "_id": "1"}}, {"id": "1", "title": "hello"}]` — two NDJSON lines. */
function buildCreate(meta: Map<string, unknown>, input: BulkActionInput): Map<string, unknown>[] {
  if (!meta.has('_id')) {
    throw new Error('[bulk-builder] create: "id" is required');
  }
  if (input.doc == null) {
    throw new Error('[bulk-builder] create: "doc" is required');
  }
  return [new Map<string, unknown>([['create', meta]]), input.doc as Map<string, unknown>];
}

/** Output: `[{"index": {"_index": "mycore", "_id": "1"}}, {"id": "1", "title": "hello"}]` — two NDJSON lines. `_id` optional. */
function buildIndex(meta: Map<string, unknown>, input: BulkActionInput): Map<string, unknown>[] {
  if (input.doc == null) {
    throw new Error('[bulk-builder] index: "doc" is required');
  }
  return [new Map<string, unknown>([['index', meta]]), input.doc as Map<string, unknown>];
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
 * `actions` is the flattened concatenation of `buildBulkAction(...)` results.
 *
 * Example flow:
 *   Solr input:  POST /solr/mycore/update  {"add":[{"doc":{"id":"1"}},{"doc":{"id":"2"}}]}
 *   Caller does: actions = [...buildBulkAction('index', {index, id:'1', doc}),
 *                            ...buildBulkAction('index', {index, id:'2', doc})]
 *                setBulkRequest(ctx, 'mycore', actions, {refresh: true})
 *   OS output:   POST /mycore/_bulk?refresh=true
 *                {"index":{"_index":"mycore","_id":"1"}}
 *                {"id":"1","title":"hello"}
 *                {"index":{"_index":"mycore","_id":"2"}}
 *                {"id":"2","title":"world"}
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

  const targetParams = new URLSearchParams();
  if (options.refresh) {
    targetParams.set('refresh', 'true');
  }
  const query = targetParams.toString();
  const suffix = query ? '?' + query : '';
  ctx.msg.set('URI', '/' + collection + '/_bulk' + suffix);
  ctx.msg.set('method', 'POST');

  // Replace Content-Type case-insensitively. The Java proxy uses headers.add()
  // (not set()), so any surviving casing variant would create a duplicate.
  const headers = ctx.msg.get('headers');
  if (headers && typeof headers.keys === 'function') {
    const toDelete = Array.from(headers.keys() as Iterable<string>)
      .filter((k: string) => k.toLowerCase() === 'content-type');
    for (const k of toDelete) {
      headers.delete(k);
    }
    headers.set('content-type', 'application/x-ndjson');
  }

  if (!ctx.msg.get('payload')) ctx.msg.set('payload', new Map());
  const payload = ctx.msg.get('payload');

  // Enforce single-body invariant: exactly one of inlinedJsonBody /
  // inlinedJsonSequenceBodies is set at a time (matches replayer contract).
  payload.delete('inlinedJsonBody');
  payload.set('inlinedJsonSequenceBodies', actions);

  // Clear ctx.body so request.transform.ts's post-pipeline write does not
  // re-populate inlinedJsonBody from an earlier state.
  ctx.body = new Map();
}
