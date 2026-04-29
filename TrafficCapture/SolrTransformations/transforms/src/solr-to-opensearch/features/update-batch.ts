/**
 * Batch operations — routes array-based add/delete commands to OpenSearch _bulk.
 *
 * Entry points:
 *   - /update/json/docs with array body [{doc1},{doc2}]
 *   - /update {"add":[{doc:{id:1}},{doc:{id:2}}]}
 *   - /update {"delete":["1","2"]}
 *   - /update {"add":[...], "delete":[...]} (mixed)
 *   - /update {"add":[...], "delete":[...], "commit":{}} (mixed + commit)
 *
 * All paths produce a single POST /_bulk request via setBulkRequest.
 * commit=true / commitWithin / {"commit":{}} → ?refresh=true on the _bulk URL.
 */
import type { RequestContext } from '../context';
import { buildBulkAction, setBulkRequest } from './bulk/bulk-builder';

/** Detect arrays — works for both JS arrays and GraalVM Java ArrayLists. */
function isArrayLike(val: any): boolean {
  if (val == null || typeof val === 'string') return false;
  return Array.isArray(val) || (typeof val[Symbol.iterator] === 'function' && typeof val.get !== 'function');
}

/** Determine if refresh should be set based on URL params or commit command presence. */
function shouldRefresh(ctx: RequestContext, hasCommitCommand: boolean): boolean {
  return ctx.params.get('commit') === 'true'
    || ctx.params.has('commitWithin')
    || hasCommitCommand;
}

/**
 * Handle batch add from /update/json/docs with array body.
 * Each element is a raw document (not wrapped in {doc:...}).
 */
export function handleJsonDocsArray(ctx: RequestContext, items: Iterable<any>): void {
  const actions: Map<string, unknown>[] = [];
  for (const rawDoc of items) {
    validateDoc(rawDoc);
    const id = rawDoc.get('id');
    if (id == null || id === '') {
      throw new Error('[update-batch] add: each document must have an "id" field');
    }
    actions.push(...buildBulkAction('index', {
      index: ctx.collection!,
      id: String(id),
      doc: rawDoc,
    }));
  }
  if (actions.length === 0) {
    throw new Error('[update-batch] add: array is empty — nothing to index');
  }
  setBulkRequest(ctx, ctx.collection!, actions, {
    refresh: shouldRefresh(ctx, false),
  });
}

/**
 * Handle batch add from {"add":[{doc:{...}}, {doc:{...}}]}.
 * Each element is an add-command wrapper with a "doc" field.
 */
export function handleBatchAdd(ctx: RequestContext, items: Iterable<any>): void {
  const actions: Map<string, unknown>[] = [];
  for (const item of items) {
    validateAddItem(item);
    const doc = item.get('doc');
    const id = doc.get('id');
    if (id == null || id === '') {
      throw new Error('[update-batch] add: each document must have an "id" field');
    }
    actions.push(...buildBulkAction('index', {
      index: ctx.collection!,
      id: String(id),
      doc,
    }));
  }
  if (actions.length === 0) {
    throw new Error('[update-batch] add: array is empty — nothing to index');
  }
  setBulkRequest(ctx, ctx.collection!, actions, {
    refresh: shouldRefresh(ctx, false),
  });
}

/**
 * Handle bulk delete by IDs: {"delete":["1","2","3"]}.
 */
export function handleBatchDelete(ctx: RequestContext, ids: Iterable<any>): void {
  const actions: Map<string, unknown>[] = [];
  for (const rawId of ids) {
    const id = rawId != null ? String(rawId).trim() : '';
    if (!id) {
      throw new Error('[update-batch] delete: each ID must be a non-empty string');
    }
    actions.push(...buildBulkAction('delete', {
      index: ctx.collection!,
      id,
    }));
  }
  if (actions.length === 0) {
    throw new Error('[update-batch] delete: array is empty — nothing to delete');
  }
  setBulkRequest(ctx, ctx.collection!, actions, {
    refresh: shouldRefresh(ctx, false),
  });
}

/**
 * Handle mixed commands: {"add":[...], "delete":[...], "commit":{}}.
 * Flattens add + delete into one _bulk actions array. Commit folds into ?refresh=true.
 */
export function handleMixedCommands(ctx: RequestContext): void {
  const body = ctx.body;
  const actions: Map<string, unknown>[] = [];
  const hasCommit = body.has('commit');

  if (body.has('add')) {
    collectAddActions(body.get('add'), ctx.collection!, actions);
  }

  if (body.has('delete')) {
    collectDeleteActions(body.get('delete'), ctx.collection!, actions);
  }

  if (actions.length === 0 && !hasCommit) {
    throw new Error('[update-batch] mixed: no actionable commands found');
  }

  if (actions.length > 0) {
    setBulkRequest(ctx, ctx.collection!, actions, {
      refresh: shouldRefresh(ctx, hasCommit),
    });
  } else {
    throw new Error('[update-batch] mixed: commit-only mixed command should use commit handler');
  }
}

/** Collect index actions from add data (array or single command). */
function collectAddActions(addData: any, collection: string, actions: Map<string, unknown>[]): void {
  if (isArrayLike(addData)) {
    for (const item of addData) {
      actions.push(...buildAddAction(item, collection));
    }
  } else if (addData && typeof addData.get === 'function') {
    actions.push(...buildAddAction(addData, collection));
  }
}

/** Build a single index action from an add-command wrapper. */
function buildAddAction(item: any, collection: string): Map<string, unknown>[] {
  validateAddItem(item);
  const doc = item.get('doc');
  const id = doc.get('id');
  if (id == null || id === '') {
    throw new Error('[update-batch] mixed add: each document must have an "id" field');
  }
  return buildBulkAction('index', { index: collection, id: String(id), doc });
}

/** Collect delete actions from delete data (array of IDs or single {id:...}). */
function collectDeleteActions(deleteData: any, collection: string, actions: Map<string, unknown>[]): void {
  if (isArrayLike(deleteData)) {
    for (const rawId of deleteData) {
      actions.push(...buildDeleteAction(rawId, collection));
    }
  } else if (deleteData && typeof deleteData.get === 'function' && deleteData.has('id')) {
    const id = deleteData.get('id');
    if (id == null || String(id).trim() === '') {
      throw new Error('[update-batch] mixed delete: id must be non-empty');
    }
    actions.push(...buildBulkAction('delete', { index: collection, id: String(id) }));
  }
}

/** Build a single delete action from a raw ID value. */
function buildDeleteAction(rawId: any, collection: string): Map<string, unknown>[] {
  const id = rawId != null ? String(rawId).trim() : '';
  if (!id) {
    throw new Error('[update-batch] mixed delete: each ID must be non-empty');
  }
  return buildBulkAction('delete', { index: collection, id });
}

function validateDoc(doc: any): void {
  if (!doc || typeof doc.get !== 'function') {
    throw new Error('[update-batch] add: each element must be a JSON object');
  }
}

function validateAddItem(item: any): void {
  if (!item || typeof item.get !== 'function') {
    throw new Error('[update-batch] add: invalid command format — expected JSON object');
  }
  if (item.has('boost')) {
    throw new Error('[update-batch] add: document-level boost is not supported');
  }
  if (item.has('overwrite') && item.get('overwrite') === false) {
    throw new Error('[update-batch] add: overwrite=false is not supported');
  }
  const doc = item.get('doc');
  if (!doc || typeof doc.get !== 'function') {
    throw new Error('[update-batch] add: each element must contain a "doc" field');
  }
}
