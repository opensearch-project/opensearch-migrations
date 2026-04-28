/**
 * Update router — single entry point for all /update/* paths.
 *
 * Routes requests based on path and body shape:
 *
 * Supported:
 *   /update/json/docs                  → update-doc (body IS the document)
 *   /update  {"add":{"doc":{...}}}     → update-doc (unwraps add.doc)
 *   /update  {"delete":{"id":"1"}}     → delete-doc
 *
 * Not supported (fail fast):
 *   /update  {"delete":{"query":"..."}} → delete-by-query
 *   /update  {"add":{"doc":{...},"boost":N}} → document-level boost
 *   /update  {"add":{"doc":{...},"overwrite":false}} → conditional write
 *   /update  {"commit":{}}              → commit
 *   /update  {"optimize":{}}            → optimize
 *   /update  {"rollback":{}}            → rollback
 *   /update  {"add":[...]}              → bulk add (array)
 *   /update  {"delete":[...]}           → bulk delete (array)
 *   /update  mixed commands             → multiple commands in one request
 *   /update  XML body                   → only JSON supported
 *
 * Future handlers are added as new cases in dispatchCommand() —
 * no pipeline or registry changes needed.
 *
 * Response transform is also here — generic for all _doc API responses
 * (create, update, delete). Converts OpenSearch format to Solr format.
 */
import type { MicroTransform } from '../pipeline';
import type { RequestContext, ResponseContext } from '../context';
import { request as deleteDocRequest } from './delete-doc';
import { request as updateDocRequest } from './update-doc';
import { response as bulkResponse, isBulkResponse } from './bulk/bulk-response';

/** Known Solr update command keys. */
const KNOWN_COMMANDS = ['delete', 'add', 'commit', 'optimize', 'rollback'];

/** Identify the command type from the body. Returns null if no command found. */
function identifyCommand(body: any): string | null {
  if (!body || typeof body.has !== 'function') return null;
  for (const cmd of KNOWN_COMMANDS) {
    if (body.has(cmd)) return cmd;
  }
  return null;
}

/** Check if body has multiple commands. */
function hasMultipleCommands(body: any): boolean {
  let count = 0;
  for (const cmd of KNOWN_COMMANDS) {
    if (body.has(cmd)) count++;
    if (count > 1) return true;
  }
  return false;
}

/** Detect arrays — works for both JS arrays and GraalVM Java ArrayLists. */
function isArrayLike(val: any): boolean {
  if (val == null || typeof val === 'string') return false;
  return Array.isArray(val) || (typeof val[Symbol.iterator] === 'function' && typeof val.get !== 'function');
}

/** Extract body keys for error messages. */
function bodyKeys(body: any): string {
  try {
    return Array.from(body.keys()).join(', ');
  } catch {
    return '<unknown>';
  }
}

/** Check if the path is the /update/json/docs shortcut. */
function isJsonDocsPath(uri: string): boolean {
  return /\/update\/json\/docs(\?|$)/.test(uri);
}

export const request: MicroTransform<RequestContext> = {
  name: 'update-router',
  apply: (ctx) => {
    // Fail fast on non-JSON content types (XML not supported)
    const headers = ctx.msg.get('headers');
    const contentType = headers?.get?.('Content-Type') || headers?.get?.('content-type') || '';
    if (contentType && !String(contentType).includes('json')) {
      throw new Error(`[update-router] dispatch: only JSON content type is supported, got '${contentType}'`);
    }

    // Solr's /update only accepts POST
    const method = ctx.msg.get('method');
    if (method && method !== 'POST') {
      throw new Error(`[update-router] dispatch: only POST method is accepted for /update, got '${method}'`);
    }

    const uri = ctx.msg.get('URI') || '';

    // /update/json/docs — body IS the document.
    // Basic validation before delegating to update-doc handler.
    if (isJsonDocsPath(uri)) {
      if (!ctx.body || ctx.body.size === 0) {
        throw new Error('[update-router] json-docs: request body is empty — expected a JSON document');
      }
      updateDocRequest.apply(ctx);
      return;
    }

    // /update — parse command from body and dispatch
    dispatchCommand(ctx);
  },
};

function dispatchCommand(ctx: RequestContext): void {
  const body = ctx.body;

  if (!body || body.size === 0) {
    throw new Error('[update-router] dispatch: request body is empty or not JSON — only JSON content type is supported');
  }

  if (hasMultipleCommands(body)) {
    throw new Error('[update-router] dispatch: mixed commands in a single request are not supported yet');
  }

  const command = identifyCommand(body);
  if (!command) {
    throw new Error(`[update-router] dispatch: no recognized command in request body (keys: ${bodyKeys(body)})`);
  }

  const commandData = body.get(command);

  if (isArrayLike(commandData)) {
    throw new Error(`[update-router] ${command}: array/bulk operations are not supported yet — send one command at a time`);
  }

  const handler = COMMAND_HANDLERS[command];
  if (!handler) {
    throw new Error(`[update-router] ${command}: command is not supported yet`);
  }
  handler(ctx, commandData);
}

/** Handler registry — add new command handlers here. */
const COMMAND_HANDLERS: Record<string, (ctx: RequestContext, data: any) => void> = {
  delete: handleDelete,
  add: handleAdd,
};

function handleDelete(ctx: RequestContext, data: any): void {
  if (!data || typeof data.has !== 'function') {
    throw new Error('[update-router] delete: invalid command format — expected JSON object');
  }
  if (data.has('query')) {
    throw new Error('[update-router] delete: delete-by-query is not supported yet');
  }
  // Fail fast on unsupported Solr delete fields
  const SUPPORTED_DELETE_FIELDS = new Set(['id']);
  for (const key of data.keys()) {
    if (!SUPPORTED_DELETE_FIELDS.has(key)) {
      throw new Error(`[update-router] delete: unsupported field '${key}' — only 'id' is supported`);
    }
  }
  ctx.body = data;
  deleteDocRequest.apply(ctx);
}

function handleAdd(ctx: RequestContext, data: any): void {
  if (!data || typeof data.get !== 'function') {
    throw new Error('[update-router] add: invalid command format — expected JSON object');
  }
  if (data.has('boost')) {
    throw new Error('[update-router] add: document-level boost is not supported — OpenSearch has no equivalent');
  }
  if (data.has('overwrite') && data.get('overwrite') === false) {
    throw new Error('[update-router] add: overwrite=false is not supported — OpenSearch always overwrites');
  }
  const doc = data.get('doc');
  if (!doc || typeof doc.get !== 'function') {
    throw new Error('[update-router] add: command must contain a "doc" field with a JSON object');
  }
  ctx.body = doc;
  updateDocRequest.apply(ctx);
}

/**
 * Response transform — single entry point for all /update/* responses.
 *
 * Match-and-dispatch between two OpenSearch response shapes:
 *   - Single-doc: {_id, result, ...}   — from PUT /_doc/{id}, DELETE /_doc/{id}
 *   - Bulk:       {took, errors, items}— from POST /_bulk (used by PR 2+)
 *
 * Single-doc mapping:
 *   OpenSearch: {"_index":"mycore","_id":"1","result":"created|updated|deleted",...}
 *   Solr:       {"responseHeader":{"status":0,"QTime":N}}
 *
 *   Result mapping:
 *     created/updated/deleted → status 0 (success)
 *     not_found → status 0 (Solr treats delete of missing doc as success —
 *                           verified against Solr 8/9)
 *     anything else → status 1 (error)
 *
 *   QTime is 0 because OpenSearch's _doc response does not include processing
 *   time. This is a known approximation — monitoring tools should not rely on
 *   this value for update operations through the shim.
 *
 * Bulk mapping: delegated to bulk-response.apply (preserves `took` as QTime,
 * aggregates per-item failures into a Solr-shaped errors array). See
 * LIMITATIONS shortcode BULK-PARTIAL-FAILURE.
 */
function isSingleDocResponse(ctx: ResponseContext): boolean {
  return ctx.responseBody.has('result') && ctx.responseBody.has('_id');
}

function applySingleDocResponse(ctx: ResponseContext): void {
  const result = ctx.responseBody.get('result');
  const status = (result === 'created' || result === 'updated' || result === 'deleted' || result === 'not_found') ? 0 : 1;

  const keys = Array.from(ctx.responseBody.keys());
  for (const key of keys) {
    ctx.responseBody.delete(key);
  }

  ctx.responseBody.set(
    'responseHeader',
    new Map<string, unknown>([
      ['status', status],
      ['QTime', 0],
    ]),
  );
}

export const response: MicroTransform<ResponseContext> = {
  name: 'update-response',
  match: (ctx) => isSingleDocResponse(ctx) || isBulkResponse(ctx.responseBody),
  apply: (ctx) => {
    if (isBulkResponse(ctx.responseBody)) {
      bulkResponse.apply(ctx);
      return;
    }
    if (isSingleDocResponse(ctx)) {
      applySingleDocResponse(ctx);
    }
  },
};
